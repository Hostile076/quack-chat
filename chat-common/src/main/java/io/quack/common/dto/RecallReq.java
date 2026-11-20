package io.quack.common.dto;

/** 撤回消息请求（只能撤自己发的、2 分钟内的消息） */
public record RecallReq(String messageId) {
}
