package io.quack.common.dto;

/** 编辑消息请求（只能改自己发的文本消息） */
public record EditReq(String messageId, String content) {
}
