package io.quack.common.dto;

/**
 * 登录请求。
 *
 * @param account  可以是 QC 号（推荐，唯一），也可以是用户名（仅在用户名唯一时可用）
 * @param password 密码
 * @param deviceId 设备标识：同一设备重新登录会顶掉旧连接，不同设备可以同时在线；
 *                 旧客户端不传时按 {@code default} 处理
 */
public record LoginReq(String account, String password, String deviceId) {

    /** 兼容旧调用：单设备 */
    public LoginReq(String account, String password) {
        this(account, password, "default");
    }
}
