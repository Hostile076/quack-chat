package io.quack.common.dto;

import io.quack.common.model.ChatGroup;
import io.quack.common.model.Friend;
import io.quack.common.model.User;

import java.util.List;
import java.util.Map;

/**
 * 登录响应：一次性把“我是谁、我的好友、我的群、未读数”带给客户端。
 *
 * @param token  访问 HTTP 接口（文件上传等）用的令牌
 * @param unread 会话 key -> 未读条数（服务端权威值，换设备/重装后也能恢复角标）
 */
public record LoginResp(User user, List<Friend> friends, List<ChatGroup> groups,
                        String token, Map<String, Integer> unread) {

    /** 兼容旧调用 */
    public LoginResp(User user, List<Friend> friends, List<ChatGroup> groups) {
        this(user, friends, groups, null, Map.of());
    }

    /** 兼容旧调用 */
    public LoginResp(User user, List<Friend> friends, List<ChatGroup> groups, String token) {
        this(user, friends, groups, token, Map.of());
    }
}
