package io.quack.common.dto;

/**
 * 发起好友申请。
 *
 * @param toId    对方 QC 号
 * @param message 验证消息，可空
 * @param remark  给对方的备注（昵称）；空表示用对方用户名/昵称
 */
public record FriendRequestReq(String toId, String message, String remark) {

    /** 兼容旧调用 */
    public FriendRequestReq(String toId, String message) {
        this(toId, message, null);
    }
}