package io.quack.common.model;

/**
 * 一条群公告。
 *
 * @param id         公告 id
 * @param text       公告正文
 * @param authorId   发布者 QC 号
 * @param authorName 发布者昵称快照
 * @param ts         发布时间
 */
public record GroupAnnouncement(String id, String text, String authorId,
                                String authorName, long ts) {
}
