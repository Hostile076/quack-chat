package io.quack.server.dispatch.handler;

import io.quack.common.dto.UserSearchReq;
import io.quack.common.dto.UserSearchResp;
import io.quack.common.protocol.Cmd;
import io.quack.server.dispatch.ChatContext;
import io.quack.server.dispatch.CommandHandler;
import io.quack.server.service.UserService;
import org.springframework.stereotype.Component;

/**
 * 搜索用户：QC 号或用户名。
 *
 * <p>排序规则在 {@link UserService#search} 里，Handler 只负责转发。
 */
@Component
public class UserSearchHandler implements CommandHandler {

    private final UserService userService;

    public UserSearchHandler(UserService userService) {
        this.userService = userService;
    }

    @Override
    public String cmd() {
        return Cmd.FRIEND_SEARCH;
    }

    @Override
    public void handle(ChatContext ctx) {
        UserSearchReq req = ctx.payload(UserSearchReq.class);
        ctx.reply(Cmd.FRIEND_SEARCH_RESP,
                new UserSearchResp(userService.search(req.keyword(), ctx.userId(), 30)));
    }
}
