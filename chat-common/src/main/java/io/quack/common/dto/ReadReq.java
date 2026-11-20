package io.quack.common.dto;

/**
 * 上报已读：某个会话读到哪儿了。
 *
 * <p>优先用 {@code seq}（会话内序号）表达位点；{@code ts} 仅为兼容旧客户端保留，
 * 两者都传时服务端以 seq 为准。
 */
public record ReadReq(String peerId, String chatType, long ts, long seq) {

    /** 兼容旧调用：只有时间戳 */
    public ReadReq(String peerId, String chatType, long ts) {
        this(peerId, chatType, ts, 0);
    }
}
