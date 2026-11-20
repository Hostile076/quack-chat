package io.quack.common.model;

/**
 * 通话信令。
 *
 * <p>只做「呼叫 / 接听 / 拒绝 / 挂断」的控制链路，**不承载音视频媒体流**；
 * 真正接通媒体需要 WebRTC（采集、编解码、NAT 穿透），那是一个独立的大工程。
 *
 * @param callId   本次通话 id
 * @param fromId   主叫 QC 号
 * @param fromName 主叫昵称
 * @param toId     被叫 QC 号
 * @param type     INVITE / ACCEPT / REJECT / HANGUP / ENDED
 * @param ts       时间
 */
public record CallSignal(String callId, String fromId, String fromName, String toId,
                         String type, long ts) {

    public static final String INVITE = "INVITE";
    public static final String ACCEPT = "ACCEPT";
    public static final String REJECT = "REJECT";
    public static final String HANGUP = "HANGUP";
    public static final String ENDED = "ENDED";
}
