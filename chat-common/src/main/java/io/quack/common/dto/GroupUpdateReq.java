package io.quack.common.dto;

/**
 * 修改群资料（仅群主）。
 *
 * @param groupId      群号
 * @param name         新群名，null 表示不改
 * @param announcement 新群公告，null 表示不改；空串表示清空
 * @param avatar       新群头像 URL，null 表示不改
 */
public record GroupUpdateReq(String groupId, String name, String announcement, String avatar) {

    /** 兼容旧调用 */
    public GroupUpdateReq(String groupId, String name, String announcement) {
        this(groupId, name, announcement, null);
    }
}
