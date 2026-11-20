package io.quack.common.dto;

import io.quack.common.model.ChatType;

/** “正在输入”通知，转发给对端（群聊则广播给其他成员） */
public record TypingNotify(String from, String fromName, String to, ChatType chatType) {
}
