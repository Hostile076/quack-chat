package io.quack.common.model;

import java.util.List;

/**
 * 群详情（群主页用）。
 *
 * @param id            群号，如 q10001
 * @param name          群名称
 * @param ownerId       群主 QC 号
 * @param announcement  最新一条群公告（兼容旧字段），可为空
 * @param memberCount   成员数
 * @param members       成员列表（含群主/管理员角色与群昵称）
 * @param avatar        群头像 URL，可为空
 * @param announcements 全部群公告（新 → 旧）
 */
public record GroupDetail(
        String id,
        String name,
        String ownerId,
        String announcement,
        int memberCount,
        List<GroupMember> members,
        String avatar,
        List<GroupAnnouncement> announcements
) {

    /** 兼容旧调用：只有一条公告字符串 */
    public GroupDetail(String id, String name, String ownerId, String announcement,
                       int memberCount, List<GroupMember> members, String avatar) {
        this(id, name, ownerId, announcement, memberCount, members, avatar, List.of());
    }

    /** 兼容旧调用 */
    public GroupDetail(String id, String name, String ownerId, String announcement,
                       int memberCount, List<GroupMember> members) {
        this(id, name, ownerId, announcement, memberCount, members, null, List.of());
    }

    public List<GroupAnnouncement> announcementList() {
        return announcements == null ? List.of() : announcements;
    }
}
