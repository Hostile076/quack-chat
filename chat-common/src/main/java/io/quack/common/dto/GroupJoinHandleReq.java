package io.quack.common.dto;

/** 同意/拒绝入群申请 */
public record GroupJoinHandleReq(String requestId, boolean accept) {
}
