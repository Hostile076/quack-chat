package io.quack.server.service;

import io.quack.common.dto.MomentCommentReq;
import io.quack.common.dto.MomentEditReq;
import io.quack.common.dto.MomentLikeReq;
import io.quack.common.dto.MomentListReq;
import io.quack.common.dto.MomentMediaItem;
import io.quack.common.dto.MomentPublishReq;
import io.quack.common.dto.MomentShareReq;
import io.quack.common.dto.MomentStatsResp;
import io.quack.common.model.Moment;
import io.quack.common.model.MomentComment;
import io.quack.common.model.MomentMedia;
import io.quack.common.model.User;
import io.quack.common.protocol.Cmd;
import io.quack.common.protocol.Envelope;
import io.quack.common.protocol.JsonCodec;
import io.quack.server.support.BizException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * QC 空间：动态（文字 + 图片/视频）、可见范围、点赞（唯一可取消）、评论/回复、
 * 转发、相册、统计、好友动态流。
 *
 * <p>可见范围：
 * <ul>
 *   <li>PUBLIC：所有人可见（默认）</li>
 *   <li>FRIENDS：仅作者的好友可见</li>
 *   <li>PRIVATE：仅自己可见</li>
 * </ul>
 */
@Service
public class MomentService {

    private static final int MAX_TEXT = 1000;
    private static final int MAX_MEDIA = 9;
    private static final int MAX_COMMENT = 300;

    private final Map<String, Moment> moments = new ConcurrentHashMap<>();
    /** momentId -> 点赞用户集合（唯一约束） */
    private final Map<String, Set<String>> likes = new ConcurrentHashMap<>();
    /** momentId -> 评论（按时间升序） */
    private final Map<String, List<MomentComment>> comments = new ConcurrentHashMap<>();
    /** momentId -> 转发数（被转发的是原动态） */
    private final Map<String, Integer> shareCounts = new ConcurrentHashMap<>();

    private final FriendService friendService;
    private final PushService pushService;
    private final UserService userService;

    public MomentService(FriendService friendService, PushService pushService,
                         UserService userService) {
        this.friendService = friendService;
        this.pushService = pushService;
        this.userService = userService;
    }

    // ------------------------------------------------------------------
    // 发布 / 编辑 / 转发
    // ------------------------------------------------------------------

    public Moment publish(User author, MomentPublishReq req) {
        String text = req.text() == null ? "" : req.text().trim();
        if (text.length() > MAX_TEXT) {
            throw BizException.badRequest("动态内容不能超过 " + MAX_TEXT + " 字");
        }
        List<MomentMedia> media = validateMedia(req.media());
        if (text.isEmpty() && media.isEmpty()) {
            throw BizException.badRequest("写点什么，或者配一张图片/视频");
        }
        Moment moment = new Moment(UUID.randomUUID().toString(), author.id(),
                author.displayName(), author.avatar(), text, media,
                System.currentTimeMillis(), 0, 0, 0, false,
                normalizeVisibility(req.visibility()), null, false);
        moments.put(moment.id(), moment);
        return view(moment, author.id());
    }

    /** 编辑：只能改自己的；文字/可见范围可改，媒体保持不变 */
    public Moment edit(User editor, MomentEditReq req) {
        Moment moment = requireMoment(req.momentId());
        if (!moment.authorId().equals(editor.id())) {
            throw BizException.forbidden("只能编辑自己的动态");
        }
        String text = req.text() == null ? "" : req.text().trim();
        if (text.length() > MAX_TEXT) {
            throw BizException.badRequest("动态内容不能超过 " + MAX_TEXT + " 字");
        }
        if (text.isEmpty() && moment.mediaOrEmpty().isEmpty()) {
            throw BizException.badRequest("动态不能是空的");
        }
        Moment updated = new Moment(moment.id(), moment.authorId(), moment.authorName(),
                moment.authorAvatar(), text, moment.mediaOrEmpty(), moment.ts(),
                moment.likes(), moment.comments(), moment.shares(), moment.likedByMe(),
                normalizeVisibility(req.visibility()), moment.shared(), true);
        moments.put(updated.id(), updated);
        Moment view = view(updated, editor.id());
        // 自己和好友端都刷新；PRIVATE 只给自己
        java.util.Set<String> receivers = new java.util.HashSet<>();
        receivers.add(editor.id());
        if (!"PRIVATE".equalsIgnoreCase(updated.visibilityOrPublic())) {
            receivers.addAll(friendService.friendIdsOf(editor.id()));
        }
        for (String receiver : receivers) {
            pushService.forwardTransient(receiver, Envelope.of(Cmd.MOMENT_EDITED_PUSH, 0, view));
        }
        return view;
    }

    /** 转发：生成一条带原动态快照的新动态 */
    public Moment share(User author, MomentShareReq req) {
        Moment source = requireMoment(req.sourceId());
        requireVisible(source, author.id());
        Moment root = source.hasShared() ? source.shared() : source;
        final Moment rootSnapshot = snapshot(root);
        String text = req.text() == null ? "" : req.text().trim();
        if (text.length() > MAX_TEXT) {
            throw BizException.badRequest("转发语不能超过 " + MAX_TEXT + " 字");
        }
        Moment share = new Moment(UUID.randomUUID().toString(), author.id(),
                author.displayName(), author.avatar(), text, List.of(),
                System.currentTimeMillis(), 0, 0, 0, false,
                normalizeVisibility(req.visibility()), rootSnapshot, false);
        moments.put(share.id(), share);
        shareCounts.merge(rootSnapshot.id(), 1, Integer::sum);
        if (!rootSnapshot.authorId().equals(author.id())) {
            pushService.forwardTransient(rootSnapshot.authorId(),
                    Envelope.of(Cmd.MOMENT_SHARED_PUSH, 0, view(rootSnapshot, rootSnapshot.authorId())));
        }
        return view(share, author.id());
    }

    /** 只保留一层转发快照，避免套娃 */
    private Moment snapshot(Moment moment) {
        return new Moment(moment.id(), moment.authorId(), moment.authorName(),
                moment.authorAvatar(), moment.text(), moment.mediaOrEmpty(), moment.ts(),
                moment.likes(), moment.comments(), moment.shares(), false,
                moment.visibilityOrPublic(), null, moment.edited());
    }

    private List<MomentMedia> validateMedia(List<MomentMedia> raw) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        if (raw.size() > MAX_MEDIA) {
            throw BizException.badRequest("最多只能带 " + MAX_MEDIA + " 张图片");
        }
        List<MomentMedia> result = new ArrayList<>();
        int videos = 0;
        for (MomentMedia media : raw) {
            if (media == null || media.url() == null || media.url().isBlank()) {
                continue;
            }
            String url = media.url().trim();
            if (!isFileUrl(url)) {
                throw BizException.badRequest("媒体地址不合法");
            }
            String type = media.type() == null ? "IMAGE" : media.type().toUpperCase();
            if ("VIDEO".equals(type)) {
                videos++;
                String cover = media.cover() == null || media.cover().isBlank() ? url : media.cover().trim();
                if (!isFileUrl(cover)) {
                    throw BizException.badRequest("视频封面地址不合法");
                }
                result.add(MomentMedia.video(url, cover,
                        Math.max(0, media.durationMs()), Math.max(0, media.width()),
                        Math.max(0, media.height())));
            } else {
                result.add(MomentMedia.image(url, Math.max(0, media.width()), Math.max(0, media.height())));
            }
        }
        if (videos > 1) {
            throw BizException.badRequest("一条动态最多带 1 个视频");
        }
        if (videos == 1 && result.size() > 1) {
            throw BizException.badRequest("视频动态不能再混图片");
        }
        return result;
    }

    /** 只接受本服务上传接口的地址：相对路径，或带 http(s) 前缀的绝对地址 */
    private static boolean isFileUrl(String url) {
        if (url.startsWith("/api/files/")) {
            return true;
        }
        try {
            java.net.URI uri = java.net.URI.create(url);
            String scheme = uri.getScheme();
            return ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                    && uri.getPath() != null && uri.getPath().startsWith("/api/files/");
        } catch (RuntimeException error) {
            return false;
        }
    }

    private String normalizeVisibility(String raw) {        if (raw == null) {
            return "PUBLIC";
        }
        String value = raw.trim().toUpperCase();
        return switch (value) {
            case "FRIENDS", "PRIVATE", "PUBLIC" -> value;
            default -> "PUBLIC";
        };
    }

    // ------------------------------------------------------------------
    // 列表 / 相册 / 统计
    // ------------------------------------------------------------------

    public List<Moment> list(String requesterId, MomentListReq req) {
        int limit = req.limit() <= 0 ? 20 : Math.min(req.limit(), 50);
        long before = req.beforeTs() <= 0 ? Long.MAX_VALUE : req.beforeTs();
        Set<String> authors = null;
        if (req.qcId() == null || req.qcId().isBlank()) {
            authors = new LinkedHashSet<>();
            if (requesterId != null) {
                authors.add(requesterId);
                authors.addAll(friendService.friendIdsOf(requesterId));
            }
        }
        final Set<String> visibleAuthors = authors;
        return moments.values().stream()
                .filter(m -> req.qcId() != null && !req.qcId().isBlank()
                        ? m.authorId().equals(req.qcId())
                        : visibleAuthors.contains(m.authorId()))
                .filter(m -> visible(m, requesterId))
                .filter(m -> m.ts() < before)
                .sorted(Comparator.comparingLong(Moment::ts).reversed())
                .limit(limit)
                .map(m -> view(m, requesterId))
                .toList();
    }

    /** 相册：可见动态里的图片/视频，按动态时间倒序 */
    public List<MomentMediaItem> media(String requesterId, String qcId, long beforeTs, int limit) {
        int size = limit <= 0 ? 60 : Math.min(limit, 200);
        long before = beforeTs <= 0 ? Long.MAX_VALUE : beforeTs;
        List<MomentMediaItem> items = new ArrayList<>();
        moments.values().stream()
                .filter(m -> qcId == null || qcId.isBlank() || m.authorId().equals(qcId))
                .filter(m -> visible(m, requesterId))
                .filter(m -> m.ts() < before)
                .sorted(Comparator.comparingLong(Moment::ts).reversed())
                .forEach(m -> {
                    for (MomentMedia media : m.mediaOrEmpty()) {
                        items.add(new MomentMediaItem(m.id(), m.authorId(), m.authorName(), media, m.ts()));
                    }
                });
        return items.size() > size ? items.subList(0, size) : items;
    }

    public MomentStatsResp stats(String requesterId, String qcId) {
        int posts = 0;
        int likeTotal = 0;
        int commentTotal = 0;
        int shareTotal = 0;
        int photos = 0;
        int videos = 0;
        for (Moment moment : moments.values()) {
            boolean mine = qcId == null || qcId.isBlank() || moment.authorId().equals(qcId);
            if (!mine || !visible(moment, requesterId)) {
                continue;
            }
            posts++;
            likeTotal += likes.getOrDefault(moment.id(), Set.of()).size();
            commentTotal += comments.getOrDefault(moment.id(), List.of()).size();
            shareTotal += shareCounts.getOrDefault(moment.id(), 0);
            for (MomentMedia media : moment.mediaOrEmpty()) {
                if (media.isVideo()) {
                    videos++;
                } else {
                    photos++;
                }
            }
        }
        return new MomentStatsResp(posts, likeTotal, commentTotal, shareTotal, photos, videos);
    }

    // ------------------------------------------------------------------
    // 点赞（唯一、可取消、可看列表）
    // ------------------------------------------------------------------

    public Moment like(String requesterId, MomentLikeReq req) {
        Moment moment = requireMoment(req.momentId());
        requireVisible(moment, requesterId);
        Set<String> users = likes.computeIfAbsent(moment.id(),
                k -> ConcurrentHashMap.newKeySet());
        boolean changed;
        if (req.liked()) {
            changed = users.add(requesterId);
        } else {
            changed = users.remove(requesterId);
        }
        Moment view = view(moment, requesterId);
        if (changed && req.liked() && !moment.authorId().equals(requesterId)) {
            pushService.forwardTransient(moment.authorId(),
                    Envelope.of(Cmd.MOMENT_LIKED_PUSH, 0, view));
        }
        return view;
    }

    public List<User> likes(String momentId, String requesterId) {
        Moment moment = requireMoment(momentId);
        requireVisible(moment, requesterId);
        Set<String> users = likes.getOrDefault(momentId, Set.of());
        List<User> result = new ArrayList<>();
        for (String userId : users) {
            userService.byId(userId).ifPresent(result::add);
        }
        result.sort(Comparator.comparing(User::id));
        return result;
    }

    // ------------------------------------------------------------------
    // 评论 / 回复
    // ------------------------------------------------------------------

    public MomentComment addComment(User author, MomentCommentReq req) {
        Moment moment = requireMoment(req.momentId());
        requireVisible(moment, author.id());
        String text = req.text() == null ? "" : req.text().trim();
        if (text.isEmpty()) {
            throw BizException.badRequest("评论不能为空");
        }
        if (text.length() > MAX_COMMENT) {
            throw BizException.badRequest("评论不能超过 " + MAX_COMMENT + " 字");
        }
        String parentId = null;
        String replyToName = null;
        if (req.parentId() != null && !req.parentId().isBlank()) {
            MomentComment parent = comments.getOrDefault(moment.id(), List.of()).stream()
                    .filter(c -> c.id().equals(req.parentId())).findFirst().orElse(null);
            if (parent == null) {
                throw BizException.badRequest("回复的评论不存在");
            }
            parentId = parent.id();
            replyToName = parent.authorName();
        }
        MomentComment comment = new MomentComment(UUID.randomUUID().toString(), moment.id(),
                author.id(), author.displayName(), author.avatar(), text,
                System.currentTimeMillis(), parentId, replyToName);
        comments.computeIfAbsent(moment.id(), k -> new java.util.concurrent.CopyOnWriteArrayList<>())
                .add(comment);
        if (!moment.authorId().equals(author.id())) {
            pushService.forwardTransient(moment.authorId(),
                    Envelope.of(Cmd.MOMENT_COMMENT_PUSH, 0, comment));
        }
        return comment;
    }

    public List<MomentComment> comments(String momentId, String requesterId) {
        Moment moment = requireMoment(momentId);
        requireVisible(moment, requesterId);
        List<MomentComment> list = new ArrayList<>(comments.getOrDefault(momentId, List.of()));
        list.sort(Comparator.comparingLong(MomentComment::ts));
        return list;
    }

    public void deleteComment(String requesterId, String commentId) {
        for (Map.Entry<String, List<MomentComment>> entry : comments.entrySet()) {
            Moment moment = moments.get(entry.getKey());
            List<MomentComment> list = entry.getValue();
            for (MomentComment comment : list) {
                if (!comment.id().equals(commentId)) {
                    continue;
                }
                boolean allowed = comment.authorId().equals(requesterId)
                        || (moment != null && moment.authorId().equals(requesterId));
                if (!allowed) {
                    throw BizException.forbidden("只能删除自己的评论，或自己动态下的评论");
                }
                list.remove(comment);
                // 同时删掉挂在这条评论下的回复
                list.removeIf(c -> comment.id().equals(c.parentId()));
                return;
            }
        }
        throw new BizException(404, "评论不存在");
    }

    // ------------------------------------------------------------------
    // 删除动态（级联）
    // ------------------------------------------------------------------

    public void delete(String momentId, String requesterId) {
        Moment moment = requireMoment(momentId);
        if (!moment.authorId().equals(requesterId)) {
            throw BizException.forbidden("只能删除自己的动态");
        }
        moments.remove(momentId);
        likes.remove(momentId);
        comments.remove(momentId);
        if (moment.shared() != null) {
            shareCounts.computeIfPresent(moment.shared().id(), (k, v) -> v <= 1 ? null : v - 1);
        }
    }

    private Moment requireMoment(String momentId) {
        if (momentId == null || momentId.isBlank()) {
            throw BizException.badRequest("缺少动态 id");
        }
        Moment moment = moments.get(momentId);
        if (moment == null) {
            throw new BizException(404, "这条动态不存在");
        }
        return moment;
    }

    private void requireVisible(Moment moment, String requesterId) {
        if (!visible(moment, requesterId)) {
            throw BizException.forbidden("没有权限查看这条动态");
        }
    }

    private boolean visible(Moment moment, String requesterId) {
        if (requesterId != null && requesterId.equals(moment.authorId())) {
            return true;
        }
        String visibility = moment.visibilityOrPublic();
        return switch (visibility) {
            case "PRIVATE" -> false;
            case "FRIENDS" -> requesterId != null
                    && friendService.friendIdsOf(moment.authorId()).contains(requesterId);
            default -> true;
        };
    }

    private Moment view(Moment moment, String requesterId) {
        int likeCount = likes.getOrDefault(moment.id(), Set.of()).size();
        int commentCount = comments.getOrDefault(moment.id(), List.of()).size();
        int shares = shareCounts.getOrDefault(moment.id(), 0);
        boolean likedByMe = requesterId != null
                && likes.getOrDefault(moment.id(), Set.of()).contains(requesterId);
        return new Moment(moment.id(), moment.authorId(), moment.authorName(),
                moment.authorAvatar(), moment.text(), moment.mediaOrEmpty(), moment.ts(),
                likeCount, commentCount, shares, likedByMe,
                moment.visibilityOrPublic(), moment.shared(), moment.edited());
    }

    // ------------------------------------------------------------------
    // 持久化（整体 JSON 快照）
    // ------------------------------------------------------------------

    public record State(List<Moment> moments, Map<String, List<String>> likes,
                        Map<String, List<MomentComment>> comments,
                        Map<String, Integer> shareCounts) {
    }

    public String exportState() {
        Map<String, List<String>> likeIds = new LinkedHashMap<>();
        likes.forEach((id, users) -> likeIds.put(id, new ArrayList<>(users)));
        Map<String, List<MomentComment>> commentMap = new LinkedHashMap<>();
        comments.forEach((id, list) -> commentMap.put(id, new ArrayList<>(list)));
        return JsonCodec.toJson(new State(new ArrayList<>(moments.values()), likeIds,
                commentMap, new LinkedHashMap<>(shareCounts)));
    }

    public void importState(String json) {
        moments.clear();
        likes.clear();
        comments.clear();
        shareCounts.clear();
        if (json == null || json.isBlank()) {
            return;
        }
        try {
            State state = JsonCodec.fromJson(json, State.class);
            if (state == null) {
                return;
            }
            if (state.moments() != null) {
                state.moments().forEach(m -> {
                    if (m != null && m.id() != null) {
                        moments.put(m.id(), m);
                    }
                });
            }
            if (state.likes() != null) {
                state.likes().forEach((id, users) -> {
                    Set<String> set = ConcurrentHashMap.newKeySet();
                    if (users != null) {
                        set.addAll(users);
                    }
                    likes.put(id, set);
                });
            }
            if (state.comments() != null) {
                state.comments().forEach((id, list) -> {
                    if (list != null) {
                        comments.put(id, new java.util.concurrent.CopyOnWriteArrayList<>(list));
                    }
                });
            }
            if (state.shareCounts() != null) {
                shareCounts.putAll(state.shareCounts());
            }
            // 旧数据没有 shareCounts：按 shared.id 重建
            if (shareCounts.isEmpty()) {
                moments.values().forEach(m -> {
                    if (m.shared() != null) {
                        shareCounts.merge(m.shared().id(), 1, Integer::sum);
                    }
                });
            }
        } catch (RuntimeException error) {
            // 坏数据不影响启动
        }
    }

    public int count() {
        return moments.size();
    }
}
