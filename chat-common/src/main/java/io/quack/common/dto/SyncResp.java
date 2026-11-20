package io.quack.common.dto;

import io.quack.common.model.ChatMessage;

import java.util.List;

/**
 * 增量同步结果。
 *
 * @param messages  按 seq 升序的消息（只包含 seq > sinceSeq 的部分）
 * @param latestSeq 服务端该会话当前最大 seq；客户端应把它记下来
 * @param hasMore   是否还有更早的缺口没返回（本次达到 limit）
 */
public record SyncResp(List<ChatMessage> messages, long latestSeq, boolean hasMore) {
}
