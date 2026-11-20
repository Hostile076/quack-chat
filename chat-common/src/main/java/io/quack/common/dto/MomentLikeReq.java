package io.quack.common.dto;

/**
 * 点赞/取消点赞。
 *
 * @param momentId 动态 id
 * @param liked    true=点赞，false=取消；同样的操作重复执行不会重复计数
 */
public record MomentLikeReq(String momentId, boolean liked) {
}
