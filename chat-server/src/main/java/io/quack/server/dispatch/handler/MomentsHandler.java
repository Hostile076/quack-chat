package io.quack.server.dispatch.handler;

import io.quack.common.dto.MomentCommentReq;
import io.quack.common.dto.MomentCommentsResp;
import io.quack.common.dto.MomentEditReq;
import io.quack.common.dto.MomentLikeReq;
import io.quack.common.dto.MomentLikesResp;
import io.quack.common.dto.MomentListReq;
import io.quack.common.dto.MomentListResp;
import io.quack.common.dto.MomentMediaResp;
import io.quack.common.dto.MomentPublishReq;
import io.quack.common.dto.MomentResp;
import io.quack.common.dto.MomentShareReq;
import io.quack.common.dto.MomentStatsResp;
import io.quack.common.model.Moment;
import io.quack.common.model.MomentComment;
import io.quack.common.model.User;
import io.quack.common.protocol.Cmd;
import io.quack.server.dispatch.ChatContext;
import io.quack.server.dispatch.MultiCommandHandler;
import io.quack.server.service.MomentService;
import io.quack.server.service.UserService;
import io.quack.server.support.BizException;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * QC 空间：发布 / 列表（空间或好友流）/ 点赞 / 评论 / 删除。
 *
 * <p>对标 OSSN 的 wall post：点赞唯一且可取消、评论可删除、动态可级联删除。
 */
@Component
public class MomentsHandler implements MultiCommandHandler {

    private final MomentService momentService;
    private final UserService userService;

    public MomentsHandler(MomentService momentService, UserService userService) {
        this.momentService = momentService;
        this.userService = userService;
    }

    @Override
    public List<String> cmds() {
        return List.of(Cmd.MOMENT_PUBLISH, Cmd.MOMENT_LIST, Cmd.MOMENT_LIKE, Cmd.MOMENT_DELETE,
                Cmd.MOMENT_COMMENT_ADD, Cmd.MOMENT_COMMENT_LIST, Cmd.MOMENT_COMMENT_DELETE,
                Cmd.MOMENT_EDIT, Cmd.MOMENT_SHARE, Cmd.MOMENT_LIKES, Cmd.MOMENT_MEDIA,
                Cmd.MOMENT_STATS);
    }

    @Override
    public void handle(ChatContext ctx) {
        switch (ctx.cmd()) {
            case Cmd.MOMENT_PUBLISH -> publish(ctx);
            case Cmd.MOMENT_LIST -> list(ctx);
            case Cmd.MOMENT_LIKE -> like(ctx);
            case Cmd.MOMENT_DELETE -> delete(ctx);
            case Cmd.MOMENT_COMMENT_ADD -> addComment(ctx);
            case Cmd.MOMENT_COMMENT_LIST -> listComments(ctx);
            case Cmd.MOMENT_COMMENT_DELETE -> deleteComment(ctx);
            case Cmd.MOMENT_EDIT -> edit(ctx);
            case Cmd.MOMENT_SHARE -> share(ctx);
            case Cmd.MOMENT_LIKES -> likes(ctx);
            case Cmd.MOMENT_MEDIA -> media(ctx);
            case Cmd.MOMENT_STATS -> stats(ctx);
            default -> ctx.fail(404, "未知命令: " + ctx.cmd());
        }
    }

    private User me(ChatContext ctx) {
        return userService.byId(ctx.userId())
                .orElseThrow(() -> BizException.unauthorized("账号状态异常"));
    }

    private void publish(ChatContext ctx) {
        MomentPublishReq req = ctx.payload(MomentPublishReq.class);
        Moment moment = momentService.publish(me(ctx), req);
        ctx.reply(Cmd.MOMENT_OK_RESP, new MomentResp(moment));
    }

    private void list(ChatContext ctx) {
        MomentListReq req = ctx.payload(MomentListReq.class);
        int limit = req.limit() <= 0 ? 20 : Math.min(req.limit(), 50);
        List<Moment> moments = momentService.list(ctx.userId(),
                new MomentListReq(req.qcId(), req.beforeTs(), limit));
        long next = moments.isEmpty() ? 0 : moments.get(moments.size() - 1).ts();
        ctx.reply(Cmd.MOMENT_LIST_RESP,
                new MomentListResp(moments, next, moments.size() >= limit));
    }

    private void like(ChatContext ctx) {
        MomentLikeReq req = ctx.payload(MomentLikeReq.class);
        Moment moment = momentService.like(ctx.userId(), req);
        ctx.reply(Cmd.MOMENT_OK_RESP, new MomentResp(moment));
    }

    private void delete(ChatContext ctx) {
        MomentCommentReq req = ctx.payload(MomentCommentReq.class);
        momentService.delete(req.momentId(), ctx.userId());
        ctx.reply(Cmd.MOMENT_OK_RESP, new MomentResp(null));
    }

    private void addComment(ChatContext ctx) {
        MomentCommentReq req = ctx.payload(MomentCommentReq.class);
        MomentComment comment = momentService.addComment(me(ctx), req);
        ctx.reply(Cmd.MOMENT_OK_RESP, new MomentCommentsResp(List.of(comment)));
    }

    private void listComments(ChatContext ctx) {
        MomentCommentReq req = ctx.payload(MomentCommentReq.class);
        ctx.reply(Cmd.MOMENT_COMMENTS_RESP,
                new MomentCommentsResp(momentService.comments(req.momentId(), ctx.userId())));
    }

    private void edit(ChatContext ctx) {
        MomentEditReq req = ctx.payload(MomentEditReq.class);
        ctx.reply(Cmd.MOMENT_OK_RESP, new MomentResp(momentService.edit(me(ctx), req)));
    }

    private void share(ChatContext ctx) {
        MomentShareReq req = ctx.payload(MomentShareReq.class);
        ctx.reply(Cmd.MOMENT_OK_RESP, new MomentResp(momentService.share(me(ctx), req)));
    }

    private void likes(ChatContext ctx) {
        MomentLikeReq req = ctx.payload(MomentLikeReq.class);
        ctx.reply(Cmd.MOMENT_LIKES_RESP,
                new MomentLikesResp(momentService.likes(req.momentId(), ctx.userId())));
    }

    private void media(ChatContext ctx) {
        MomentListReq req = ctx.payload(MomentListReq.class);
        int limit = req.limit() <= 0 ? 60 : Math.min(req.limit(), 200);
        var items = momentService.media(ctx.userId(), req.qcId(), req.beforeTs(), limit);
        long next = items.isEmpty() ? 0 : items.get(items.size() - 1).ts();
        ctx.reply(Cmd.MOMENT_MEDIA_RESP,
                new MomentMediaResp(items, next, items.size() >= limit));
    }

    private void stats(ChatContext ctx) {
        MomentListReq req = ctx.payload(MomentListReq.class);
        ctx.reply(Cmd.MOMENT_STATS_RESP, momentService.stats(ctx.userId(), req.qcId()));
    }

    private void deleteComment(ChatContext ctx) {
        MomentCommentReq req = ctx.payload(MomentCommentReq.class);
        if (req.commentId() == null || req.commentId().isBlank()) {
            throw BizException.badRequest("缺少评论 id");
        }
        momentService.deleteComment(ctx.userId(), req.commentId());
        ctx.reply(Cmd.MOMENT_OK_RESP, new MomentResp(null));
    }
}
