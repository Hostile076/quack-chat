package io.quack.server.dispatch;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 命令分发器：cmd -&gt; Handler。
 *
 * <p>Spring 会把所有 {@link CommandHandler} 实现注入进来，启动时建立路由表。
 * 这样“协议扩展”退化成“新增一个类”，而不是在一大段 if-else 里改代码。
 */
@Component
public class CommandDispatcher {

    private static final Logger log = LoggerFactory.getLogger(CommandDispatcher.class);

    private final Map<String, CommandHandler> handlers = new HashMap<>();

    private final Map<String, MultiCommandHandler> multiHandlers = new HashMap<>();

    public CommandDispatcher(List<CommandHandler> commandHandlers,
                             List<MultiCommandHandler> multiCommandHandlers) {
        for (CommandHandler handler : commandHandlers) {
            register(handler.cmd(), handler);
        }
        for (MultiCommandHandler handler : multiCommandHandlers) {
            for (String cmd : handler.cmds()) {
                MultiCommandHandler previous = multiHandlers.put(cmd, handler);
                if (previous != null) {
                    throw new IllegalStateException("命令重复注册: " + cmd);
                }
            }
        }
        log.info("命令路由注册完成，共 {} 条: {}", handlers.size() + multiHandlers.size(), allCmds());
    }

    private void register(String cmd, CommandHandler handler) {
        CommandHandler previous = handlers.put(cmd, handler);
        if (previous != null) {
            throw new IllegalStateException("命令重复注册: " + cmd);
        }
    }

    private java.util.Set<String> allCmds() {
        java.util.Set<String> all = new java.util.TreeSet<>(handlers.keySet());
        all.addAll(multiHandlers.keySet());
        return all;
    }

    public void dispatch(ChatContext ctx) {
        CommandHandler handler = handlers.get(ctx.cmd());
        if (handler != null) {
            handler.handle(ctx);
            return;
        }
        MultiCommandHandler multi = multiHandlers.get(ctx.cmd());
        if (multi == null) {
            ctx.fail(404, "未知命令: " + ctx.cmd());
            return;
        }
        multi.handle(ctx);
    }

    /** 供单元测试断言路由表 */
    public boolean supports(String cmd) {
        return handlers.containsKey(cmd) || multiHandlers.containsKey(cmd);
    }
}
