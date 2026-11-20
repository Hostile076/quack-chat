package io.quack.common.dto;

/**
 * 修改自己的资料。
 *
 * @param username  新用户名，null 表示不改
 * @param nickname  昵称，null 表示不改
 * @param avatar    头像 URL，null 表示不改
 * @param signature 个性签名
 * @param gender    男 / 女 / 保密
 * @param birthday  yyyy-MM-dd，可空
 * @param region    所在地
 * @param identity  身份/职业
 */
public record UpdateProfileReq(
        String username,
        String nickname,
        String avatar,
        String signature,
        String gender,
        String birthday,
        String region,
        String identity
) {

    /** 兼容旧调用：只改展示字段 */
    public UpdateProfileReq(String nickname, String signature, String gender,
                            String birthday, String region, String identity) {
        this(null, nickname, null, signature, gender, birthday, region, identity);
    }
}
