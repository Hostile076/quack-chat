package io.quack.common.dto;

import io.quack.common.model.User;

/**
 * 注册响应。
 *
 * <p>注册成功后服务端会分配一个 QC 号，客户端要把它显示给用户
 * （以后登录用 QC 号最稳，用户名可能重名）。
 */
public record RegisterResp(User user) {
}
