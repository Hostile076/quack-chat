package io.quack.common.dto;

/** 列空间动态：qcId 为空表示“好友动态流”，否则是某个人的空间 */
public record MomentListReq(String qcId, long beforeTs, int limit) {
}
