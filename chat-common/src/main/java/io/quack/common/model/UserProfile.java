package io.quack.common.model;

/**
 * 用户资料（个人主页用）。
 *
 * <p>年龄和星座不落库，客户端按生日算 —— 存了就得每年更新，属于典型的冗余。
 *
 * @param qcId      QC 号
 * @param username  登录名
 * @param nickname  昵称
 * @param avatar    头像 URL，可为空
 * @param signature 个性签名
 * @param gender    性别：男 / 女 / 保密
 * @param birthday  生日，格式 yyyy-MM-dd，可为空
 * @param region    所在地
 * @param identity  身份/职业（如“学生”）
 * @param likes     收到的赞
 * @param createdAt 注册时间（毫秒）
 */
public record UserProfile(
        long qcId,
        String username,
        String nickname,
        String avatar,
        String signature,
        String gender,
        String birthday,
        String region,
        String identity,
        int likes,
        long createdAt
) {

    /** 兼容旧调用：没有头像 */
    public UserProfile(long qcId, String username, String nickname, String signature,
                       String gender, String birthday, String region, String identity,
                       int likes, long createdAt) {
        this(qcId, username, nickname, null, signature, gender, birthday, region, identity,
                likes, createdAt);
    }

    public String id() {
        return String.valueOf(qcId);
    }

    public String displayName() {
        return nickname == null || nickname.isBlank() ? username : nickname;
    }
}
