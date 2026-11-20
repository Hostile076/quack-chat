package io.quack.common.dto;

import io.quack.common.model.ChatGroup;

import java.util.List;

/** 群搜索结果 */
public record GroupSearchResp(List<ChatGroup> groups) {
}
