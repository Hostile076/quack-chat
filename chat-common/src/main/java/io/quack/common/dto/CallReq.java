package io.quack.common.dto;

/**
 * 通话信令请求。
 *
 * @param callId 通话 id（发起时为空，由服务端生成）
 * @param toId   对方 QC 号（发起时必填）
 */
public record CallReq(String callId, String toId) {
}
