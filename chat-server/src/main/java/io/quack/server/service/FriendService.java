package io.quack.server.service;

import io.quack.common.model.Friend;
import io.quack.server.ws.SessionManager;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 好友关系（双向好友，无“关注”概念）。
 *
 * <p>数据上是邻接表：{@code userId -> {friendId...}}。生产环境对应
 * {@code t_friend(user_id, friend_id, remark)} 两张对称记录，或一张带小 id 大 id 的无向边表。
 */
@Service
public class FriendService {

    /** userId -> 好友 id 集合 */
    private final Map<String, Set<String>> graph = new ConcurrentHashMap<>();
    /** "userId:friendId" -> 备注 */
    private final Map<String, String> remarks = new ConcurrentHashMap<>();

    private final UserService userService;
    private final SessionManager sessionManager;

    public FriendService(UserService userService, SessionManager sessionManager) {
        this.userService = userService;
        this.sessionManager = sessionManager;
    }

    /** 建立双向好友关系，可分别设置备注 */
    public void link(String a, String b, String remarkForA, String remarkForB) {
        graph.computeIfAbsent(a, k -> ConcurrentHashMap.newKeySet()).add(b);
        graph.computeIfAbsent(b, k -> ConcurrentHashMap.newKeySet()).add(a);
        if (remarkForA != null && !remarkForA.isBlank()) {
            remarks.put(key(a, b), remarkForA);
        }
        if (remarkForB != null && !remarkForB.isBlank()) {
            remarks.put(key(b, a), remarkForB);
        }
    }

    /** 设置好友备注；空串表示清除 */
    public void setRemark(String userId, String friendId, String remark) {
        if (!areFriends(userId, friendId)) {
            throw io.quack.server.support.BizException.badRequest("对方不是你的好友");
        }
        if (remark == null || remark.isBlank()) {
            remarks.remove(key(userId, friendId));
        } else {
            remarks.put(key(userId, friendId), remark.trim());
        }
    }

    // ------------------------------------------------------------------
    // 持久化
    // ------------------------------------------------------------------

    public java.util.List<io.quack.server.store.PersistedState.Friendship> snapshotFriendships() {
        // 每个「有向」关系一条记录：(a, b) 的备注只属于 a 这一侧；
        // 主键 (a,b) 天然唯一，正好存进 SQLite 的 friendships 表
        java.util.List<io.quack.server.store.PersistedState.Friendship> list = new java.util.ArrayList<>();
        for (Map.Entry<String, Set<String>> entry : graph.entrySet()) {
            String owner = entry.getKey();
            for (String other : entry.getValue()) {
                list.add(new io.quack.server.store.PersistedState.Friendship(
                        owner, other, remarks.get(key(owner, other))));
            }
        }
        return list;
    }

    public void restoreFriendships(
            java.util.List<io.quack.server.store.PersistedState.Friendship> list) {
        if (list == null) {
            return;
        }
        graph.clear();
        remarks.clear();
        for (io.quack.server.store.PersistedState.Friendship friendship : list) {
            if (friendship == null || friendship.a() == null || friendship.b() == null) {
                continue;
            }
            graph.computeIfAbsent(friendship.a(), k -> ConcurrentHashMap.newKeySet()).add(friendship.b());
            graph.computeIfAbsent(friendship.b(), k -> ConcurrentHashMap.newKeySet()).add(friendship.a());
            if (friendship.remark() != null && !friendship.remark().isBlank()) {
                remarks.put(key(friendship.a(), friendship.b()), friendship.remark());
            }
        }
    }

    public Set<String> friendIdsOf(String userId) {
        return graph.getOrDefault(userId, Set.of());
    }

    public boolean areFriends(String a, String b) {
        return friendIdsOf(a).contains(b);
    }

    /** 好友列表，在线状态实时从会话表取，排序稳定（备注/昵称） */
    public List<Friend> friendsOf(String userId) {
        return friendIdsOf(userId).stream()
                .map(userService::byId)
                .flatMap(Optional::stream)
                .map(u -> new Friend(u.id(), u.nickname(), remarks.get(key(userId, u.id())),
                        sessionManager.isOnline(u.id()), u.avatar()))
                .sorted(Comparator.comparing(Friend::displayName))
                .toList();
    }

    /** 生成“我眼里这个好友”的视图（含备注与实时在线状态），用于加好友后的回包 */
    public Optional<Friend> viewOf(String userId, String friendId) {
        return userService.byId(friendId).map(u -> new Friend(
                u.id(), u.nickname(), remarks.get(key(userId, u.id())), sessionManager.isOnline(u.id()),
                u.avatar()));
    }

    public void updateRemark(String userId, String friendId, String remark) {
        if (remark == null || remark.isBlank()) {
            remarks.remove(key(userId, friendId));
        } else {
            remarks.put(key(userId, friendId), remark);
        }
    }

    private static String key(String userId, String friendId) {
        return userId + ":" + friendId;
    }
}
