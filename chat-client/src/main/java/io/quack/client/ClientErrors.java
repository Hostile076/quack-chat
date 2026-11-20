package io.quack.client;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

/**
 * 把底层异常翻译成用户能看懂的话。
 *
 * <p>典型场景：用户只双击了客户端、没启动服务端，这时会抛出
 * {@link ConnectException}（Connection refused）。直接把它显示成
 * “注册失败：ConnectException” 只会让人去查日志，
 * 所以这里统一翻译成“下一步该做什么”。
 */
public final class ClientErrors {

    private ClientErrors() {
    }

    public static String describe(Throwable error) {
        Throwable cause = unwrap(error);
        if (cause == null) {
            return "未知错误";
        }

        if (cause instanceof ConnectException) {
            return "连不上服务器，请先启动服务端（双击 QuackChatServer.exe，或运行 start-chat.bat），"
                    + "并确认地址填写正确";
        }
        if (cause instanceof HttpConnectTimeoutException || cause instanceof SocketTimeoutException) {
            return "连接服务器超时，请检查服务端是否启动、地址与端口是否正确";
        }
        if (cause instanceof HttpTimeoutException || cause instanceof TimeoutException) {
            return "服务器响应超时，请稍后重试";
        }
        if (cause instanceof UnknownHostException) {
            return "服务器地址无法解析，请检查地址是否写错";
        }
        if (cause instanceof IllegalArgumentException
                && cause.getMessage() != null
                && cause.getMessage().contains("URI")) {
            return "服务器地址格式不对，应形如 ws://127.0.0.1:8080/ws";
        }
        if (cause instanceof java.nio.channels.ClosedChannelException) {
            return "连接已断开，请重新登录";
        }

        String message = cause.getMessage();
        if (message == null || message.isBlank()) {
            return cause.getClass().getSimpleName();
        }
        return message;
    }

    private static Throwable unwrap(Throwable error) {
        Throwable current = error;
        while (current != null
                && (current instanceof CompletionException || current instanceof ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }
}
