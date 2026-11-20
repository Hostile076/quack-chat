package io.quack.common.model;

/**
 * 一条聊天消息。
 *
 * @param id          服务端生成的全局唯一 id（UUID，后续可换雪花算法）
 * @param clientMsgId 客户端生成的 id，用于 ACK 配对与幂等去重（网络重发时不重复落库）
 * @param from        发送者 userId
 * @param fromName    发送者昵称快照，避免群聊里每条消息都回查用户表
 * @param to          私聊为对方 userId，群聊为 groupId
 * @param chatType    私聊 / 群聊
 * @param msgType     文本 / 图片 / 文件 / 系统
 * @param content     消息正文
 * @param ts          服务端落库时间（毫秒），展示用
 * @param fromAvatar  发送者头像快照
 * @param seq         会话内自增序号：排序、增量同步、已读位点的唯一依据。
 *                    0 表示还没分配（客户端乐观消息）
 * @param edited      是否被编辑过（客户端显示“已编辑”）
 */
public record ChatMessage(
        String id,
        String clientMsgId,
        String from,
        String fromName,
        String to,
        ChatType chatType,
        MsgType msgType,
        String content,
        long ts,
        String fromAvatar,
        long seq,
        boolean edited
) {

    /** 兼容旧调用：不带发送者头像、序号、编辑标记 */
    public ChatMessage(String id, String clientMsgId, String from, String fromName, String to,
                       ChatType chatType, MsgType msgType, String content, long ts) {
        this(id, clientMsgId, from, fromName, to, chatType, msgType, content, ts, null, 0, false);
    }

    /** 兼容旧调用：不带序号、编辑标记 */
    public ChatMessage(String id, String clientMsgId, String from, String fromName, String to,
                       ChatType chatType, MsgType msgType, String content, long ts, String fromAvatar) {
        this(id, clientMsgId, from, fromName, to, chatType, msgType, content, ts, fromAvatar, 0, false);
    }

    /** 兼容旧调用：不带编辑标记 */
    public ChatMessage(String id, String clientMsgId, String from, String fromName, String to,
                       ChatType chatType, MsgType msgType, String content, long ts,
                       String fromAvatar, long seq) {
        this(id, clientMsgId, from, fromName, to, chatType, msgType, content, ts, fromAvatar, seq, false);
    }

    /** 会话 key：私聊双向同一个 key，群聊按群号 */
    public String conversationKey() {
        return chatType == ChatType.PRIVATE
                ? (from.compareTo(to) <= 0 ? "PRIVATE:" + from + ":" + to : "PRIVATE:" + to + ":" + from)
                : "GROUP:" + to;
    }
}
