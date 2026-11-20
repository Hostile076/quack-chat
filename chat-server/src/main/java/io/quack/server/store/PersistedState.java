package io.quack.server.store;

import io.quack.common.model.ChatMessage;
import io.quack.common.model.FriendRequest;
import io.quack.common.model.GroupJoinRequest;
import io.quack.common.model.User;
import io.quack.common.model.UserProfile;

import java.util.List;
import java.util.Map;

/**
 * 一次完整的状态快照：服务层与 SQLite 之间的传输对象。
 *
 * <p>写库时按表拆分；读库时再拼回这个对象，交给各 service 恢复。
 */
public record PersistedState(
        int version,
        List<AccountState> accounts,
        List<UserProfile> profiles,
        long qcSequence,
        List<Friendship> friendships,
        List<FriendRequest> requests,
        List<GroupState> groups,
        long groupSequence,
        List<ChatMessage> messages,
        Map<String, Map<String, ReadPos>> readAt,
        Map<String, Map<String, Map<String, Long>>> offlineCursor,
        Map<String, Map<String, Integer>> unread,
        List<GroupJoinRequest> joinRequests
) {

    public static final int CURRENT_VERSION = 3;

    public static PersistedState empty() {
        return new PersistedState(CURRENT_VERSION, List.of(), List.of(), 10000L,
                List.of(), List.of(), List.of(), 10000L, List.of(), Map.of(), Map.of(), Map.of(),
                List.of());
    }

    /** 已读位点：seq 是权威，ts 兼容旧数据 */
    public record ReadPos(long seq, long ts) {
    }

    /** 账户：密码只存哈希 */
    public record AccountState(User user, String passwordHash) {
    }

    /** 好友边 + 备注（备注是单向的，所以一条边可能对应两条记录） */
    public record Friendship(String a, String b, String remark) {
    }

    /** 群：成员与角色；aliases = 群昵称（userId -> 昵称）；announcementList = 多条公告；admins = 管理员 */
    public record GroupState(String id, String name, String ownerId, String announcement,
                             String avatar, List<String> members,
                             Map<String, String> aliases,
                             List<io.quack.common.model.GroupAnnouncement> announcementList,
                             List<String> admins) {

        /** 兼容旧数据：没有群昵称/公告列表/管理员 */
        public GroupState(String id, String name, String ownerId, String announcement,
                          String avatar, List<String> members) {
            this(id, name, ownerId, announcement, avatar, members, Map.of(), List.of(), List.of());
        }

        /** 兼容：有群昵称但没有公告列表/管理员 */
        public GroupState(String id, String name, String ownerId, String announcement,
                          String avatar, List<String> members, Map<String, String> aliases) {
            this(id, name, ownerId, announcement, avatar, members, aliases, List.of(), List.of());
        }

        /** 兼容：有群昵称/公告但没有管理员 */
        public GroupState(String id, String name, String ownerId, String announcement,
                          String avatar, List<String> members, Map<String, String> aliases,
                          List<io.quack.common.model.GroupAnnouncement> announcementList) {
            this(id, name, ownerId, announcement, avatar, members, aliases, announcementList, List.of());
        }
    }
}
