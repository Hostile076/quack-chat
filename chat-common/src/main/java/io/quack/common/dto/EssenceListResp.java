package io.quack.common.dto;

import io.quack.common.model.ChatMessage;

import java.util.List;

/** 群精华消息列表 */
public record EssenceListResp(List<ChatMessage> messages) {
}
