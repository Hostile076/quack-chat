package io.quack.common.dto;

/**
 * 处理好友申请。
 *
 * @param requestId 申请 id
 * @param accept    true 同意，false 拒绝
 */
public record FriendHandleReq(String requestId, boolean accept) {
}
