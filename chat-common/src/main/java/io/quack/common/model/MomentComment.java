package io.quack.common.model;

/** QC 空间的一条评论（支持一级回复） */
public record MomentComment(String id, String momentId, String authorId, String authorName,
                            String authorAvatar, String text, long ts,
                            String parentId, String replyToName) {

    /** 兼容旧调用：没有回复关系 */
    public MomentComment(String id, String momentId, String authorId, String authorName,
                         String authorAvatar, String text, long ts) {
        this(id, momentId, authorId, authorName, authorAvatar, text, ts, null, null);
    }
}
