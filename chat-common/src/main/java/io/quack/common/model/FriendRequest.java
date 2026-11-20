package io.quack.common.model;

/**
 * 一条好友申请。
 *
 * @param id         申请 id（UUID）
 * @param fromId     申请人 QC 号
 * @param fromName   申请人昵称（冗余，避免列表里再查一次）
 * @param toId       被申请人 QC 号
 * @param message    验证消息
 * @param ts         发起时间
 * @param status     PENDING / ACCEPTED / REJECTED
 * @param remark     申请时附带的备注（对方同意后写到好友关系上）
 * @param fromAvatar 申请人头像 URL（冗余，申请列表直接显示头像）
 */
public record FriendRequest(
        String id,
        String fromId,
        String fromName,
        String toId,
        String message,
        long ts,
        String status,
        String remark,
        String fromAvatar
) {

    /** 兼容旧调用 */
    public FriendRequest(String id, String fromId, String fromName, String toId,
                         String message, long ts, String status, String remark) {
        this(id, fromId, fromName, toId, message, ts, status, remark, null);
    }

    /** 兼容旧调用 */
    public FriendRequest(String id, String fromId, String fromName, String toId,
                         String message, long ts, String status) {
        this(id, fromId, fromName, toId, message, ts, status, null, null);
    }

    public static final String PENDING = "PENDING";
    public static final String ACCEPTED = "ACCEPTED";
    public static final String REJECTED = "REJECTED";

    public boolean isPending() {
        return PENDING.equals(status);
    }
}
