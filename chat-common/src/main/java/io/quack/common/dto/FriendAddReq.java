package io.quack.common.dto;

/**
 * 加好友请求（按用户名精确查找）。
 *
 * <p>骨架阶段是“单向发起、直接成为好友”（类似早期 QQ 的免验证）；
 * 接好友申请流程时，把它拆成 request / handle 两个命令即可。
 */
public record FriendAddReq(String username) {
}
