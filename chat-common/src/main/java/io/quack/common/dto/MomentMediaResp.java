package io.quack.common.dto;

import java.util.List;

/** 某人的相册（按时间倒序的图片/视频） */
public record MomentMediaResp(List<MomentMediaItem> items, long nextBeforeTs, boolean hasMore) {
}
