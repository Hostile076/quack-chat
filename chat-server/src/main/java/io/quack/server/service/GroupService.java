package io.quack.server.service;

import io.quack.common.model.ChatGroup;
import io.quack.common.model.GroupDetail;
import io.quack.common.model.GroupMember;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 群组服务。
 *
 * <p>群号规则：{@code q + 数字}，从 10001 开始顺序颁发。
 * 群消息是写扩散的（逐条投递给在线成员），成员上千的大群必须改读扩散。
 */
@Service
public class GroupService {

    public static final long FIRST_GROUP_NUMBER = 10001L;

    private final Map<String, ChatGroup> groups = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> members = new ConcurrentHashMap<>();
    private final Map<String, String> owners = new ConcurrentHashMap<>();
    private final Map<String, String> announcements = new ConcurrentHashMap<>();
    /** 群公告（可多条，新 → 旧） */
    private final Map<String, java.util.List<io.quack.common.model.GroupAnnouncement>> announcementLists =
            new ConcurrentHashMap<>();
    /** groupId -> 管理员 userId 集合 */
    private final Map<String, Set<String>> admins = new ConcurrentHashMap<>();
    private final Map<String, String> avatars = new ConcurrentHashMap<>();
    /** groupId -> userId -> 群昵称 */
    private final Map<String, Map<String, String>> aliases = new ConcurrentHashMap<>();
    private final AtomicLong groupSequence = new AtomicLong(FIRST_GROUP_NUMBER);
    private final UserService userService;

    public GroupService(UserService userService) {
        this.userService = userService;
    }

    public synchronized ChatGroup create(String name, String ownerId, List<String> memberIds) {
        long number = groupSequence.getAndIncrement();
        String id = "q" + number;
        Set<String> set = new java.util.concurrent.ConcurrentSkipListSet<>();
        set.add(ownerId);
        set.addAll(memberIds);
        members.put(id, set);
        owners.put(id, ownerId);
        announcements.put(id, "");
        announcementLists.put(id, new java.util.concurrent.CopyOnWriteArrayList<>());
        admins.put(id, java.util.concurrent.ConcurrentHashMap.newKeySet());
        ChatGroup group = summary(id, name, set.size());
        groups.put(id, group);
        return group;
    }

    /**
     * 构造群摘要。
     *
     * <p>群头像存在独立的 {@code avatars} 表里（历史原因），所有“重建摘要”的地方
     * 都必须走这里，否则一次邀请/踢人/退群就会把群头像写成 null，
     * 表现为会话列表和侧栏的群头像突然变回首字母。
     */
    private ChatGroup summary(String groupId, String name, int memberCount) {
        return new ChatGroup(groupId, name, memberCount, avatars.get(groupId));
    }

    // ------------------------------------------------------------------
    // 持久化
    // ------------------------------------------------------------------

    public java.util.List<io.quack.server.store.PersistedState.GroupState> snapshotGroups() {
        java.util.List<io.quack.server.store.PersistedState.GroupState> list = new java.util.ArrayList<>();
        for (ChatGroup group : groups.values()) {
            java.util.List<String> ids = new java.util.ArrayList<>(
                    members.getOrDefault(group.id(), java.util.Set.of()));
            list.add(new io.quack.server.store.PersistedState.GroupState(
                    group.id(), group.name(), owners.get(group.id()),
                    announcements.getOrDefault(group.id(), ""),
                    avatars.get(group.id()), ids,
                    new java.util.LinkedHashMap<>(
                            aliases.getOrDefault(group.id(), Map.of())),
                    new java.util.ArrayList<>(
                            announcementLists.getOrDefault(group.id(), java.util.List.of())),
                    new java.util.ArrayList<>(
                            admins.getOrDefault(group.id(), java.util.Set.of()))));
        }
        return list;
    }

    public void restoreGroups(java.util.List<io.quack.server.store.PersistedState.GroupState> list) {
        if (list == null) {
            return;
        }
        groups.clear();
        members.clear();
        owners.clear();
        announcements.clear();
        avatars.clear();
        aliases.clear();
        announcementLists.clear();
        admins.clear();
        for (io.quack.server.store.PersistedState.GroupState state : list) {
            if (state == null || state.id() == null) {
                continue;
            }
            java.util.Set<String> ids = new java.util.concurrent.ConcurrentSkipListSet<>();
            if (state.members() != null) {
                ids.addAll(state.members());
            }
            members.put(state.id(), ids);
            groups.put(state.id(), new ChatGroup(state.id(), state.name(), ids.size(), state.avatar()));
            if (state.ownerId() != null) {
                owners.put(state.id(), state.ownerId());
            }
            announcements.put(state.id(), state.announcement() == null ? "" : state.announcement());
            if (state.avatar() != null) {
                avatars.put(state.id(), state.avatar());
            }
            if (state.aliases() != null && !state.aliases().isEmpty()) {
                aliases.put(state.id(), new ConcurrentHashMap<>(state.aliases()));
            }
            java.util.List<io.quack.common.model.GroupAnnouncement> news =
                    new java.util.concurrent.CopyOnWriteArrayList<>();
            if (state.announcementList() != null && !state.announcementList().isEmpty()) {
                news.addAll(state.announcementList());
            } else if (state.announcement() != null && !state.announcement().isBlank()) {
                // 旧数据只有一条字符串公告，迁移成列表
                news.add(new io.quack.common.model.GroupAnnouncement(
                        "legacy-" + state.id(), state.announcement(), state.ownerId(),
                        userService.nicknameOf(state.ownerId()), 0));
            }
            announcementLists.put(state.id(), news);
            Set<String> adminSet = java.util.concurrent.ConcurrentHashMap.newKeySet();
            if (state.admins() != null) {
                adminSet.addAll(state.admins());
            }
            admins.put(state.id(), adminSet);
            groupSequence.set(Math.max(groupSequence.get(), qr(state.id())));
        }
    }

    private long qr(String groupId) {
        try {
            return Long.parseLong(groupId.substring(1));
        } catch (RuntimeException error) {
            return 0;
        }
    }

    public long groupSequenceNow() {
        return groupSequence.get();
    }

    public void restoreGroupSequence(long value) {
        if (value > 0) {
            groupSequence.set(Math.max(groupSequence.get(), value));
        }
    }

    public List<ChatGroup> groupsOf(String userId) {
        return groups.values().stream()
                .filter(g -> membersOf(g.id()).contains(userId))
                .sorted(Comparator.comparingLong(ChatGroup::qcNumber))
                .toList();
    }

    /** 按群号或群名称搜索（最多 50 条） */
    public List<ChatGroup> search(String keyword) {
        String value = keyword == null ? "" : keyword.trim().toLowerCase();
        if (value.isEmpty()) {
            return List.of();
        }
        return groups.values().stream()
                .filter(g -> g.id().toLowerCase().contains(value)
                        || (g.name() != null && g.name().toLowerCase().contains(value)))
                .sorted(Comparator.comparingLong(ChatGroup::qcNumber))
                .limit(50)
                .toList();
    }

    public Set<String> membersOf(String groupId) {
        if (groupId == null) {
            return Set.of();
        }
        return members.getOrDefault(groupId, Set.of());
    }

    /**
     * 分页遍历群成员：大群（十万级）不能一次把成员列表拷进内存。
     *
     * <p>成员集是 ConcurrentSkipListSet，按 userId 有序，可以用 tailSet 一页页往后走。
     */
    public void forEachMember(String groupId, java.util.function.Consumer<String> action) {
        Set<String> set = members.get(groupId);
        if (set == null || set.isEmpty()) {
            return;
        }
        if (set instanceof java.util.NavigableSet<String> sorted) {
            String after = null;
            while (true) {
                java.util.List<String> page = new ArrayList<>(200);
                java.util.Iterator<String> iterator = after == null
                        ? sorted.iterator() : sorted.tailSet(after, false).iterator();
                while (iterator.hasNext() && page.size() < 200) {
                    page.add(iterator.next());
                }
                if (page.isEmpty()) {
                    return;
                }
                for (String member : page) {
                    action.accept(member);
                }
                after = page.get(page.size() - 1);
                if (page.size() < 200) {
                    return;
                }
            }
        }
        set.forEach(action);
    }

    public boolean contains(String groupId, String userId) {
        return groupId != null && userId != null && membersOf(groupId).contains(userId);
    }

    public Optional<ChatGroup> byId(String id) {
        return Optional.ofNullable(groups.get(id));
    }

    /** 群主页数据：成员按“群主 → 管理员 → QC 号升序”排列 */
    public Optional<GroupDetail> detail(String groupId) {
        ChatGroup group = groups.get(groupId);
        if (group == null) {
            return Optional.empty();
        }
        String owner = owners.getOrDefault(groupId, "");
        Set<String> memberIds = membersOf(groupId);
        List<GroupMember> list = new ArrayList<>();
        for (String memberId : memberIds) {
            String role;
            if (memberId.equals(owner)) {
                role = GroupMember.OWNER;
            } else if (admins.getOrDefault(groupId, Set.of()).contains(memberId)) {
                role = GroupMember.ADMIN;
            } else {
                role = GroupMember.MEMBER;
            }
            String alias = aliasOf(groupId, memberId);
            list.add(new GroupMember(memberId, userService.nicknameOf(memberId), role,
                    userService.byId(memberId).map(io.quack.common.model.User::avatar).orElse(null),
                    alias));
        }
        list.sort(Comparator
                .comparing((GroupMember m) -> m.isOwner() ? 0 : 1)
                .thenComparingLong(m -> parse(m.userId())));
        java.util.List<io.quack.common.model.GroupAnnouncement> news =
                new java.util.ArrayList<>(announcementLists.getOrDefault(groupId, java.util.List.of()));
        String latest = news.isEmpty() ? announcements.getOrDefault(groupId, "")
                : news.get(0).text();
        return Optional.of(new GroupDetail(group.id(), group.name(), owner,
                latest, memberIds.size(), list, avatars.get(groupId), news));
    }

    public Optional<GroupDetail> update(String groupId, String name, String announcement, String avatar) {
        ChatGroup group = groups.get(groupId);
        if (group == null) {
            return Optional.empty();
        }
        String nextName = group.name();
        if (name != null && !name.isBlank()) {
            nextName = name.trim();
        }
        if (announcement != null && !announcement.isBlank()) {
            String owner = owners.getOrDefault(groupId, "");
            addAnnouncement(groupId, owner, userService.nicknameOf(owner), announcement);
        }
        if (avatar != null) {
            if (avatar.isBlank()) {
                avatars.remove(groupId);
            } else {
                avatars.put(groupId, avatar.trim());
            }
        }
        group = summary(groupId, nextName, group.memberCount());
        groups.put(groupId, group);
        return detail(groupId);
    }

    /** 追加一条群公告（新公告在最前面） */
    public synchronized Optional<GroupDetail> addAnnouncement(String groupId, String authorId,
                                                              String authorName, String text) {
        ChatGroup group = groups.get(groupId);
        if (group == null || text == null || text.isBlank()) {
            return Optional.empty();
        }
        String value = text.trim();
        if (value.length() > 500) {
            value = value.substring(0, 500);
        }
        io.quack.common.model.GroupAnnouncement announcement =
                new io.quack.common.model.GroupAnnouncement(
                        java.util.UUID.randomUUID().toString(), value, authorId,
                        authorName == null || authorName.isBlank() ? "群主" : authorName,
                        System.currentTimeMillis());
        announcementLists.computeIfAbsent(groupId,
                k -> new java.util.concurrent.CopyOnWriteArrayList<>()).add(0, announcement);
        announcements.put(groupId, value);
        return detail(groupId);
    }

    /** 兼容旧调用：不改头像 */
    public Optional<GroupDetail> update(String groupId, String name, String announcement) {
        return update(groupId, name, announcement, null);
    }

    /** 我在本群的昵称：为空则清除 */
    public synchronized Optional<GroupDetail> setAlias(String groupId, String userId, String alias) {
        if (!contains(groupId, userId)) {
            return Optional.empty();
        }
        Map<String, String> map = aliases.computeIfAbsent(groupId,
                k -> new ConcurrentHashMap<>());
        String value = alias == null ? "" : alias.trim();
        if (value.isEmpty()) {
            map.remove(userId);
        } else {
            map.put(userId, value.length() > 20 ? value.substring(0, 20) : value);
        }
        return detail(groupId);
    }

    public String aliasOf(String groupId, String userId) {
        if (groupId == null || userId == null) {
            return "";
        }
        return aliases.getOrDefault(groupId, Map.of()).getOrDefault(userId, "");
    }

    public boolean isOwner(String groupId, String userId) {
        return userId != null && userId.equals(owners.get(groupId));
    }

    public boolean isAdmin(String groupId, String userId) {
        return userId != null && admins.getOrDefault(groupId, Set.of()).contains(userId);
    }

    /** 群主或管理员：拥有群管理权限 */
    public boolean isManager(String groupId, String userId) {
        return isOwner(groupId, userId) || isAdmin(groupId, userId);
    }

    /** 群主任命管理员（权限由 Handler 校验） */
    public synchronized boolean addAdmin(String groupId, String userId) {
        if (!contains(groupId, userId) || isOwner(groupId, userId)) {
            return false;
        }
        return admins.computeIfAbsent(groupId,
                k -> java.util.concurrent.ConcurrentHashMap.newKeySet()).add(userId);
    }

    public synchronized boolean removeAdmin(String groupId, String userId) {
        Set<String> set = admins.get(groupId);
        return set != null && set.remove(userId);
    }

    /** 解散群：返回所有成员（用于推送） */
    public synchronized Set<String> dissolve(String groupId) {
        ChatGroup group = groups.get(groupId);
        if (group == null) {
            return Set.of();
        }
        Set<String> memberIds = new java.util.HashSet<>(membersOf(groupId));
        groups.remove(groupId);
        members.remove(groupId);
        owners.remove(groupId);
        announcements.remove(groupId);
        aliases.remove(groupId);
        announcementLists.remove(groupId);
        admins.remove(groupId);
        avatars.remove(groupId);
        return memberIds;
    }

    /** 退群：群主退出则转让给 QC 号最小的成员，最后一个人退出就解散 */
    public synchronized void quit(String groupId, String userId) {
        Set<String> set = members.get(groupId);
        if (set == null || !set.remove(userId)) {
            return;
        }
        aliases.computeIfPresent(groupId, (k, map) -> {
            map.remove(userId);
            return map;
        });
        ChatGroup group = groups.get(groupId);
        if (group == null) {
            return;
        }
        if (set.isEmpty()) {
            groups.remove(groupId);
            members.remove(groupId);
            owners.remove(groupId);
            announcements.remove(groupId);
            aliases.remove(groupId);
            admins.remove(groupId);
            announcementLists.remove(groupId);
            return;
        }
        if (userId.equals(owners.get(groupId))) {
            String next = admins.getOrDefault(groupId, Set.of()).stream()
                    .min(Comparator.comparingLong(GroupService::parse))
                    .orElseGet(() -> set.stream()
                            .min(Comparator.comparingLong(GroupService::parse)).orElseThrow());
            owners.put(groupId, next);
            admins.computeIfPresent(groupId, (k, map) -> {
                map.remove(next);
                return map;
            });
        }
        admins.computeIfPresent(groupId, (k, map) -> {
            map.remove(userId);
            return map;
        });
        groups.put(groupId, summary(groupId, group.name(), set.size()));
    }

    /** 邀请成员进群（权限由 Handler 校验） */
    public synchronized void invite(String groupId, List<String> userIds) {
        Set<String> set = members.get(groupId);
        ChatGroup group = groups.get(groupId);
        if (set == null || group == null || userIds == null) {
            return;
        }
        // null / 空串会让 ConcurrentHashMap 的 keySet 直接 NPE，先过滤掉
        List<String> valid = new ArrayList<>();
        for (String id : userIds) {
            if (id != null && !id.isBlank()) {
                valid.add(id.trim());
            }
        }
        if (valid.isEmpty()) {
            return;
        }
        set.addAll(valid);
        groups.put(groupId, summary(groupId, group.name(), set.size()));
        // 同时刷新群详情里的成员数缓存
        owners.computeIfAbsent(groupId, k -> "");
    }

    /** 踢人（权限由 Handler 校验） */
    public synchronized void kick(String groupId, String userId) {
        Set<String> set = members.get(groupId);
        ChatGroup group = groups.get(groupId);
        if (set == null || group == null || !set.remove(userId)) {
            return;
        }
        admins.computeIfPresent(groupId, (k, map) -> {
            map.remove(userId);
            return map;
        });
        aliases.computeIfPresent(groupId, (k, map) -> {
            map.remove(userId);
            return map;
        });
        groups.put(groupId, summary(groupId, group.name(), set.size()));
    }

    /** 转让群主 */
    public synchronized void transfer(String groupId, String newOwnerId) {
        if (!membersOf(groupId).contains(newOwnerId)) {
            return;
        }
        owners.put(groupId, newOwnerId);
        admins.computeIfPresent(groupId, (k, map) -> {
            map.remove(newOwnerId);
            return map;
        });
    }

    public void join(String groupId, String userId) {
        Set<String> set = members.get(groupId);
        ChatGroup group = groups.get(groupId);
        if (set == null || group == null || userId == null || userId.isBlank()) {
            return;
        }
        set.add(userId);
        groups.put(groupId, summary(groupId, group.name(), set.size()));
    }

    private static long parse(String id) {
        try {
            return Long.parseLong(id);
        } catch (NumberFormatException e) {
            return Long.MAX_VALUE;
        }
    }
}
