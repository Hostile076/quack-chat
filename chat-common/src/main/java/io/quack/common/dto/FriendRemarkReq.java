package io.quack.common.dto;

/**
 * 设置好友备注（昵称）。
 *
 * @param friendId 好友 QC 号
 * @param remark   备注；空串表示清除备注（显示回对方用户名/昵称）
 */
public record FriendRemarkReq(String friendId, String remark) {
}