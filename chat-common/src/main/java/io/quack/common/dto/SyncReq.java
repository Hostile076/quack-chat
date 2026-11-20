package io.quack.common.dto;

import io.quack.common.model.ChatType;

/**
 * 增量同步请求。
 *
 * <p>客户端带上每个会话已收到的最大 seq，服务端只返回比它新的消息；
 * 断线重连、换设备、重装客户端都用同一条命令补洞。
 *
 * @param peerId   私聊为对方 QC 号，群聊为群号
 * @param chatType PRIVATE / GROUP
 * @param sinceSeq 已收到的最大序号（0 表示从最早开始）
 * @param limit    本次最多返回多少条
 */
public record SyncReq(String peerId, ChatType chatType, long sinceSeq, int limit) {
}
