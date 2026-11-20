package io.quack.common.dto;

/** 任命/取消群管理员（仅群主） */
public record GroupAdminReq(String groupId, String userId) {
}
