package io.quack.common.dto;

import io.quack.common.model.User;

import java.util.List;

/** 某条动态的点赞用户列表 */
public record MomentLikesResp(List<User> users) {
}
