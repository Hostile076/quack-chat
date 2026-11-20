package io.quack.common.dto;

/** 编辑自己的动态（文字 / 可见范围；媒体这轮不改） */
public record MomentEditReq(String momentId, String text, String visibility) {
}
