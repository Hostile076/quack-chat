package io.quack.common.dto;

/**
 * 未读数推送：换设备/已读后同步各端角标。
 *
 * @param convKey 会话 key（PRIVATE:a:b / GROUP:g）
 * @param unread  未读条数
 */
public record UnreadPush(String convKey, int unread) {
}
