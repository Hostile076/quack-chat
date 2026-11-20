package io.quack.common.dto;

/**
 * 注册请求。
 *
 * <p>只允许用户填写**用户名**和**密码**：QC 号由服务端分配，昵称默认等于用户名，
 * 都不接受客户端指定。
 *
 * @param username 用户名：3~20 位字母/数字/下划线，允许重名
 * @param password 密码：6~32 位
 */
public record RegisterReq(String username, String password) {
}
