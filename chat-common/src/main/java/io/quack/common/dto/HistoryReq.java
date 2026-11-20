package io.quack.common.dto;

import io.quack.common.model.ChatType;

/**
 * 历史消息查询。
 *
 * <p>采用“游标 + 条数”而不是 offset 分页：聊天记录会不断追加，
 * offset 分页会出现重复或漏读，游标（beforeTs）天然稳定。
 *
 * @param peerId   私聊对方 userId / 群聊 groupId
 * @param chatType 会话类型
 * @param beforeTs 只取该时间戳之前的消息，0 表示从最新开始
 * @param limit    条数，服务端会限制上限
 */
public record HistoryReq(String peerId, ChatType chatType, long beforeTs, int limit) {

    public static HistoryReq latest(String peerId, ChatType chatType) {
        return new HistoryReq(peerId, chatType, 0L, 50);
    }
}
