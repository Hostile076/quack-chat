package io.quack.common.dto;

/** 说说列表 / 点赞 / 删除的通用请求：list 时 qcId 可选，like/delete 时 id 必填 */
public record MomentReq(String qcId, String id) {
}
