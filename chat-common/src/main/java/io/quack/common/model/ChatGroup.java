package io.quack.common.model;

/**
 * 群聊摘要。
 *
 * @param id          群号，格式固定为 {@code q + 5 位以上数字}，例如 {@code q10001}
 * @param name        群名称
 * @param memberCount 成员数（冗余计数）
 * @param avatar      群头像 URL，可为空（没有就用首字母占位）
 */
public record ChatGroup(String id, String name, int memberCount, String avatar) {

    /** 兼容旧调用：没有群头像 */
    public ChatGroup(String id, String name, int memberCount) {
        this(id, name, memberCount, null);
    }

    /** 群号里的数字部分，用于展示与排序 */
    public long qcNumber() {
        if (id == null || id.length() < 2) {
            return 0;
        }
        try {
            return Long.parseLong(id.substring(1));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
