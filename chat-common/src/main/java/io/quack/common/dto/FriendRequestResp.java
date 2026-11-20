package io.quack.common.dto;

import io.quack.common.model.FriendRequest;

/** 申请发起成功的回执（同时也用来做推送负载） */
public record FriendRequestResp(FriendRequest request) {
}
