package io.quack.common.dto;

import io.quack.common.model.MomentComment;

import java.util.List;

/** 某条动态的评论列表 */
public record MomentCommentsResp(List<MomentComment> comments) {
}
