package io.quack.common.dto;

import io.quack.common.model.MomentMedia;

/** 相册里的一个媒体项（来自某条动态） */
public record MomentMediaItem(String momentId, String authorId, String authorName,
                              MomentMedia media, long ts) {
}
