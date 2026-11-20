package io.quack.common.dto;

import io.quack.common.model.Friend;
import io.quack.common.model.FriendRequest;

/**
 * 处理好友申请的结果。
 *
 * @param request  处理后的申请（状态已是 ACCEPTED / REJECTED）
 * @param newFriend 同意时返回“对方”的好友信息，客户端可以直接建会话；拒绝时为 null
 */
public record FriendHandleResp(FriendRequest request, Friend newFriend) {
}
