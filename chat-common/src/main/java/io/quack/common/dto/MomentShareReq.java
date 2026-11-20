package io.quack.common.dto;

/** 转发动态：sourceId 原动态，text 为转发语（可空） */
public record MomentShareReq(String sourceId, String text, String visibility) {
}
