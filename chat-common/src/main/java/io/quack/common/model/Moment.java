package io.quack.common.model;

import java.util.List;

/**
 * QC 空间的一条动态。
 *
 * @param id           动态 id
 * @param authorId     作者 QC 号
 * @param authorName   作者昵称快照
 * @param authorAvatar 作者头像快照
 * @param text         正文（可为空：纯图片/视频/转发动态）
 * @param media        图片/视频附件（0~9 个）
 * @param ts           发布时间
 * @param likes        点赞数
 * @param comments     评论数
 * @param shares       转发数
 * @param likedByMe    当前请求者是否点过赞（服务端按请求者计算）
 * @param visibility   可见范围：PUBLIC / FRIENDS / PRIVATE
 * @param shared       转发自哪条动态（原动态快照；非转发为 null）
 * @param edited       是否编辑过
 */
public record Moment(
        String id,
        String authorId,
        String authorName,
        String authorAvatar,
        String text,
        List<MomentMedia> media,
        long ts,
        int likes,
        int comments,
        int shares,
        boolean likedByMe,
        String visibility,
        Moment shared,
        boolean edited
) {

    /** 兼容旧代码：普通公开动态 */
    public Moment(String id, String authorId, String authorName, String authorAvatar,
                  String text, List<MomentMedia> media, long ts, int likes, int comments,
                  boolean likedByMe) {
        this(id, authorId, authorName, authorAvatar, text, media, ts, likes, comments,
                0, likedByMe, "PUBLIC", null, false);
    }

    /** 兼容最老调用：纯文本动态 */
    public Moment(String id, String authorId, String authorName, String text, long ts, int likes) {
        this(id, authorId, authorName, null, text, List.of(), ts, likes, 0, false);
    }

    public List<MomentMedia> mediaOrEmpty() {
        return media == null ? List.of() : media;
    }

    public String visibilityOrPublic() {
        return visibility == null || visibility.isBlank() ? "PUBLIC" : visibility;
    }

    public boolean hasShared() {
        return shared != null;
    }
}
