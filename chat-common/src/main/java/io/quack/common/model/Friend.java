package io.quack.common.model;

/**
 * 好友条目。
 *
 * @param userId  好友的用户 id
 * @param nickname 好友昵称
 * @param remark  我给对方设置的备注（可为空）
 * @param online  当前是否在线（服务端按会话实时计算）
 * @param avatar  头像 URL（可为空；登录/加好友时一起下发，列表才能直接显示头像）
 */
public record Friend(String userId, String nickname, String remark, boolean online, String avatar) {

    /** 兼容旧调用（没有头像） */
    public Friend(String userId, String nickname, String remark, boolean online) {
        this(userId, nickname, remark, online, null);
    }

    /** 列表里显示的名字：有备注优先显示备注 */
    public String displayName() {
        return remark == null || remark.isBlank() ? nickname : remark;
    }
}
