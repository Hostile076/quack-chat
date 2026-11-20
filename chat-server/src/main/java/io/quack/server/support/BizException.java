package io.quack.server.support;

/**
 * 可预期的业务异常，统一转换成协议错误帧，不打印堆栈。
 *
 * <p>约定错误码：
 * <ul>
 *   <li>400 参数错误</li>
 *   <li>401 未认证 / 登录失败</li>
 *   <li>403 无权限（如不在该群）</li>
 *   <li>404 命令或资源不存在</li>
 *   <li>500 服务器内部错误</li>
 * </ul>
 */
public class BizException extends RuntimeException {

    private final int code;

    public BizException(int code, String message) {
        super(message);
        this.code = code;
    }

    public int code() {
        return code;
    }

    public static BizException badRequest(String message) {
        return new BizException(400, message);
    }

    public static BizException unauthorized(String message) {
        return new BizException(401, message);
    }

    public static BizException forbidden(String message) {
        return new BizException(403, message);
    }
}
