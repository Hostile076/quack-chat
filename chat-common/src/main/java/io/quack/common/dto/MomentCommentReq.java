package io.quack.common.dto;

/** 评论动态 / 删除评论 / 回复某条评论 */
public record MomentCommentReq(String momentId, String commentId, String text, String parentId) {

    public MomentCommentReq(String momentId, String commentId, String text) {
        this(momentId, commentId, text, null);
    }
}
