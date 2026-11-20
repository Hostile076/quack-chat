package io.quack.common.dto;

import java.util.List;

/**
 * 创建群聊：群名 + 要拉进来的好友。
 *
 * @param name      群名称（1~20 字）
 * @param memberIds 被拉进来的好友 QC 号（创建者自动成为群主）
 */
public record GroupCreateReq(String name, List<String> memberIds) {
}