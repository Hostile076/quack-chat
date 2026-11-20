package io.quack.server.dispatch;

/**
 * 一条命令的处理单元。
 *
 * <p>新增业务时只需要：写一个实现类 + {@code @Component}，Dispatcher 会自动收集，
 * 不需要改任何已有代码（对扩展开放，对修改关闭）。
 */
public interface CommandHandler {

    /** 该处理器负责的命令字，见 {@code Cmd} */
    String cmd();

    /** 处理一帧请求；抛出的 BizException 会被统一转成错误响应 */
    void handle(ChatContext ctx);
}
