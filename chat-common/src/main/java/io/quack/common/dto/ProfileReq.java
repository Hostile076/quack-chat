package io.quack.common.dto;

/** 查看某人资料：不传 qcId 表示看自己 */
public record ProfileReq(String qcId) {
}
