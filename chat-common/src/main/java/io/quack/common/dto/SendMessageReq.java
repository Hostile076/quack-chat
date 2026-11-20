package io.quack.common.dto;

import io.quack.common.model.ChatType;
import io.quack.common.model.MsgType;

/**
 * 发送消息请求。
 *
 * @param clientMsgId 客户端生成的临时 id（必填），服务端 ACK 时原样返回
 * @param to          私聊对方 userId / 群聊 groupId
 * @param chatType    会话类型
 * @param msgType     消息类型，缺省 TEXT
 * @param content     正文
 */
public record SendMessageReq(
        String clientMsgId,
        String to,
        ChatType chatType,
        MsgType msgType,
        String content
) {

    /** 常用的文本私聊快捷构造 */
    public static SendMessageReq text(String clientMsgId, String to, ChatType chatType, String content) {
        return new SendMessageReq(clientMsgId, to, chatType, MsgType.TEXT, content);
    }
}
