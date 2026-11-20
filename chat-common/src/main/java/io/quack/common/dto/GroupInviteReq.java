package io.quack.common.dto;

import java.util.List;

/** 邀请好友进群 */
public record GroupInviteReq(String groupId, List<String> userIds) {
}
