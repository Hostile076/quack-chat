package io.quack.common.dto;

/**
 * 批量接收确认（RECVACK）。
 *
 * <p>客户端应用完一批推送后上报该会话已收到的最大 seq；服务端据此推进这台设备的离线游标。
 * 游标推进是“已确认收到”，和“已读”是两件事。
 */
public record RecvReq(String peerId, String chatType, long seq) {
}
