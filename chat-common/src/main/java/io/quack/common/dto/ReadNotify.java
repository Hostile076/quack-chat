package io.quack.common.dto;

/**
 * 已读通知。
 *
 * @param chatType PRIVATE / GROUP
 * @param peerId   私聊为对方 id（接收者视角的会话对象），群聊为群号
 * @param readerId 谁读的
 * @param ts       读到的时间点（兼容字段）
 * @param seq      读到的会话内序号（权威字段）
 */
public record ReadNotify(String chatType, String peerId, String readerId, long ts, long seq) {

    /** 兼容旧调用 */
    public ReadNotify(String chatType, String peerId, String readerId, long ts) {
        this(chatType, peerId, readerId, ts, 0);
    }
}
