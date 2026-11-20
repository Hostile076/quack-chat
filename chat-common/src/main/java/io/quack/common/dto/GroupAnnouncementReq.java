package io.quack.common.dto;

/** 发布一条群公告（仅群主） */
public record GroupAnnouncementReq(String groupId, String text) {
}
