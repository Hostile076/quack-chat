package io.quack.common.dto;

/** 空间统计：动态数 / 获赞 / 评论 / 转发 / 图片 / 视频 */
public record MomentStatsResp(int posts, int likes, int comments, int shares,
                              int photos, int videos) {
}
