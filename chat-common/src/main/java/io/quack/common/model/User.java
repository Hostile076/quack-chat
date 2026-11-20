package io.quack.common.model;

/**
 * 用户公开信息。
 *
 * <p>QC 号（{@code qcId}）是账号的唯一身份，从 10001 开始顺序颁发，10001 是内置测试号。
 * 用户名允许重复（后注册的人可能和前面的人同名），所以**唯一标识只用 QC 号**，
 * 内部的消息路由、好友关系、会话 key 全部用 QC 号。
 *
 * @param qcId     QC 号，唯一
 * @param username 登录名，可重复
 * @param nickname 昵称
 * @param avatar   头像 URL（当前为 null，客户端用首字生成占位头像）
 */
public record User(long qcId, String username, String nickname, String avatar) {

    /** 内部统一用字符串形式传，避免到处做类型转换 */
    public String id() {
        return String.valueOf(qcId);
    }

    public String displayName() {
        return nickname == null || nickname.isBlank() ? username : nickname;
    }
}
