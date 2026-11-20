package io.quack.common.dto;

import io.quack.common.model.Moment;

/** 发布/点赞/删除后的最新说说（删除时返回 null） */
public record MomentResp(Moment moment) {
}
