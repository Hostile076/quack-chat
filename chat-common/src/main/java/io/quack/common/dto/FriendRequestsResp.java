package io.quack.common.dto;

import io.quack.common.model.FriendRequest;

import java.util.List;

/**
 * 好友申请列表。
 *
 * @param incoming 别人申请加我（待我处理）
 * @param outgoing 我发出去的申请（用于显示“等待对方验证”）
 */
public record FriendRequestsResp(List<FriendRequest> incoming, List<FriendRequest> outgoing) {
}
