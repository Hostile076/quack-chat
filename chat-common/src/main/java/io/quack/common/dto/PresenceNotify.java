package io.quack.common.dto;

/** 好友在线状态变更推送 */
public record PresenceNotify(String userId, boolean online) {
}
