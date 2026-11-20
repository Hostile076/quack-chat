package io.quack.common.dto;

import io.quack.common.model.ChatMessage;
import io.quack.common.model.ChatType;

import java.util.List;

/**
 * 历史消息响应，{@code messages} 按时间正序排列（老的在前），方便客户端直接 prepend。
 */
public record HistoryResp(String peerId, ChatType chatType, List<ChatMessage> messages, boolean hasMore) {
}
