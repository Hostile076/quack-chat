package io.quack.common.dto;

/** 设置“我在本群的昵称”：alias 为空表示清除 */
public record GroupAliasReq(String groupId, String alias) {
}
