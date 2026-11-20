package io.quack.server.dispatch;

import java.util.List;

/**
 * 一个处理器负责多条命令。
 *
 * <p>为什么需要它：像「QC 空间」的发布/列表/点赞/删除，逻辑同源、DTO 相近，
 * 拆成四个类太碎；用一个类按 {@code ctx.cmd()} 分支更清楚。
 */
public interface MultiCommandHandler {

    /** 该处理器负责的命令字列表 */
    List<String> cmds();

    void handle(ChatContext ctx);
}
