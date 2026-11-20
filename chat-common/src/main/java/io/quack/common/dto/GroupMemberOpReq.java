package io.quack.common.dto;

/**
 * 群成员操作：踢人 / 转让群主 / 精华消息。
 *
 * @param groupId  群号
 * @param userId   被操作的用户 QC 号（踢人/转让）
 * @param messageId 消息 id（精华）
 */
public record GroupMemberOpReq(String groupId, String userId, String messageId) {
}
