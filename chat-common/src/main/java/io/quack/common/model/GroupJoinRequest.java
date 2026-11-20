package io.quack.common.model;

/**
 * 入群申请。
 *
 * @param id         申请 id
 * @param groupId    群号
 * @param groupName  群名称快照
 * @param userId     申请人 QC 号
 * @param userName   申请人昵称快照
 * @param userAvatar 申请人头像快照
 * @param status     PENDING / APPROVED / REJECTED
 * @param ts         申请时间
 * @param handledBy  处理人 QC 号
 * @param handledAt  处理时间
 */
public record GroupJoinRequest(String id, String groupId, String groupName,
                               String userId, String userName, String userAvatar,
                               String status, long ts, String handledBy, long handledAt) {

    public static final String PENDING = "PENDING";
    public static final String APPROVED = "APPROVED";
    public static final String REJECTED = "REJECTED";

    public boolean pending() {
        return PENDING.equalsIgnoreCase(status);
    }
}
