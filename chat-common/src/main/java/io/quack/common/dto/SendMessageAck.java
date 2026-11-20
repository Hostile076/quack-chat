package io.quack.common.dto;

/**
 * 消息发送成功的回执。
 *
 * <p>客户端收到后应把“发送中”的本地消息替换成服务端消息 id、时间戳与会话内序号。
 * 注意：回执只代表服务端已提交，不代表对方已收到/已读（三态见协议文档）。
 *
 * @param messageId   服务端消息 id
 * @param clientMsgId 对应的客户端消息 id
 * @param ts          服务端落库时间
 * @param seq         会话内自增序号
 */
public record SendMessageAck(String messageId, String clientMsgId, long ts, long seq) {

    /** 兼容旧调用 */
    public SendMessageAck(String messageId, String clientMsgId, long ts) {
        this(messageId, clientMsgId, ts, 0);
    }
}
