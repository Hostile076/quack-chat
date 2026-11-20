package io.quack.common.model;

/**
 * 群成员（群主页九宫格用）。
 *
 * @param userId   QC 号
 * @param nickname 昵称
 * @param role     OWNER / ADMIN / MEMBER
 * @param avatar   头像 URL
 * @param alias    我在本群的昵称（群昵称），可为空
 */
public record GroupMember(String userId, String nickname, String role, String avatar, String alias) {

    /** 兼容旧调用：没有头像/群昵称 */
    public GroupMember(String userId, String nickname, String role) {
        this(userId, nickname, role, null, null);
    }

    /** 兼容旧调用：没有群昵称 */
    public GroupMember(String userId, String nickname, String role, String avatar) {
        this(userId, nickname, role, avatar, null);
    }

    public static final String OWNER = "OWNER";
    public static final String ADMIN = "ADMIN";
    public static final String MEMBER = "MEMBER";

    public boolean isOwner() {
        return OWNER.equals(role);
    }

    /** 群昵称优先，没有就用普通昵称 */
    public String displayName() {
        return alias == null || alias.isBlank() ? nickname : alias;
    }
}
