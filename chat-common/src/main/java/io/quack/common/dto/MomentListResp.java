package io.quack.common.dto;

import io.quack.common.model.Moment;

import java.util.List;

/** 动态列表（游标分页：下一页把 nextBeforeTs 再传回来） */
public record MomentListResp(List<Moment> moments, long nextBeforeTs, boolean hasMore) {
}
