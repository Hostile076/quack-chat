package io.quack.common.dto;

import io.quack.common.model.MomentMedia;

import java.util.List;

/** 发布空间动态：文字 + 图片/视频附件 + 可见范围 */
public record MomentPublishReq(String text, List<MomentMedia> media, String visibility) {

    public MomentPublishReq(String text, List<MomentMedia> media) {
        this(text, media, "PUBLIC");
    }

    public MomentPublishReq(String text) {
        this(text, List.of(), "PUBLIC");
    }
}
