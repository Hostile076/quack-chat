package io.quack.common.protocol;

/** 协议命令字。详见 docs/protocol.md */
public final class Cmd {

    /** 登录（连接建立后必须发的第一条命令）。account 可以是 QC 号或用户名 */
    public static final String LOGIN = "login";
    public static final String LOGIN_RESP = "login.resp";

    /** 注册新账号（服务端分配 QC 号） */
    public static final String REGISTER = "register";
    public static final String REGISTER_RESP = "register.resp";

    /** 心跳保活，客户端每 25s 发一次 */
    public static final String HEARTBEAT = "heartbeat";
    public static final String HEARTBEAT_RESP = "heartbeat.resp";

    /** 发送消息，私聊 / 群聊共用一个命令，用 chatType 区分 */
    public static final String MSG_SEND = "msg.send";
    /** 服务端 ACK：消息已落库（携带服务端消息 id 与时间戳） */
    public static final String MSG_ACK = "msg.ack";
    /** 客户端上报「我已读到这个时间点」 */
    public static final String MSG_READ = "msg.read";
    /** 已读回执的服务端响应（带剩余未读数，保证每个请求都有回包） */
    public static final String MSG_READ_RESP = "msg.read.resp";
    /** 已读状态推送 */
    public static final String MSG_READ_PUSH = "msg.read.push";
    /** 客户端确认“这批消息我已经收到”（批量 RECVACK，推进该设备的离线游标） */
    public static final String MSG_RECV = "msg.recv";
    /** 未读数推送（已读后同步同账号其它设备） */
    public static final String UNREAD_PUSH = "unread.push";
    /** 服务端推送新消息 */
    public static final String MSG_PUSH = "msg.push";
    /** 撤回消息（仅发送者，2 分钟内） */
    public static final String MSG_RECALL = "msg.recall";
    /** 撤回推送：会话内所有人把这条消息替换成“已撤回” */
    public static final String MSG_RECALL_PUSH = "msg.recalled";
    /** 编辑自己发的文本消息 */
    public static final String MSG_EDIT = "msg.edit";
    /** 编辑推送：会话内所有人替换成新内容 */
    public static final String MSG_EDIT_PUSH = "msg.edited";

    /** 拉取历史消息 */
    public static final String HISTORY_REQ = "history.req";
    public static final String HISTORY_RESP = "history.resp";

    /** 增量同步：按会话内 seq 补洞（断线重连 / 换设备 / 重装） */
    public static final String SYNC_REQ = "sync.req";
    public static final String SYNC_RESP = "sync.resp";

    /** 按 QC 号或用户名搜索用户 */
    public static final String FRIEND_SEARCH = "friend.search";
    public static final String FRIEND_SEARCH_RESP = "friend.search.resp";

    /** 发起好友申请 */
    public static final String FRIEND_REQUEST = "friend.request";
    public static final String FRIEND_REQUEST_RESP = "friend.request.resp";

    /** 拉取好友申请列表（收到的 + 发出的） */
    public static final String FRIEND_REQUESTS = "friend.requests";
    public static final String FRIEND_REQUESTS_RESP = "friend.requests.resp";

    /** 同意 / 拒绝好友申请 */
    public static final String FRIEND_HANDLE = "friend.handle";
    public static final String FRIEND_HANDLE_RESP = "friend.handle.resp";

    /** 好友关系建立（同意申请时给双方推，同时作为处理结果里的 newFriend） */
    public static final String FRIEND_ADDED = "friend.added";
    /** 好友资料变了（昵称/头像/用户名） */
    public static final String FRIEND_UPDATED = "friend.updated";
    /** 设置/清除好友备注（昵称） */
    public static final String FRIEND_REMARK = "friend.remark";
    public static final String FRIEND_REMARK_RESP = "friend.remark.resp";
    /** 备注变更推送给本人（多端同步） */
    public static final String FRIEND_REMARK_PUSH = "friend.remark.push";

    /** 收到新的好友申请（推给被申请人） */
    public static final String FRIEND_REQUEST_PUSH = "friend.request.push";

    /** 申请的对方已处理（推给申请人） */
    public static final String FRIEND_HANDLED_PUSH = "friend.handled.push";

    /** “正在输入”状态 */
    public static final String TYPING = "typing";
    /** 好友上下线推送 */
    public static final String PRESENCE = "presence.push";

    /** 查看用户资料 */
    public static final String PROFILE = "user.profile";
    public static final String PROFILE_RESP = "user.profile.resp";
    /** 修改自己的资料 */
    public static final String PROFILE_UPDATE = "user.update";
    public static final String PROFILE_UPDATE_RESP = "user.update.resp";
    /** 给资料页点赞 */
    public static final String PROFILE_LIKE = "user.like";
    public static final String PROFILE_LIKE_RESP = "user.like.resp";

    /** 群详情（成员列表、公告、群主） */
    /** 创建群聊（我拉好友进一个新群） */
    public static final String GROUP_CREATE = "group.create";
    public static final String GROUP_CREATE_RESP = "group.create.resp";

    public static final String GROUP_DETAIL = "group.detail";
    public static final String GROUP_DETAIL_RESP = "group.detail.resp";
    /** 修改群名 / 群公告（群主） */
    public static final String GROUP_UPDATE = "group.update";
    public static final String GROUP_UPDATE_RESP = "group.update.resp";
    /** 我在本群的昵称 */
    public static final String GROUP_ALIAS = "group.alias";
    /** 发布群公告（可多条） */
    public static final String GROUP_ANNOUNCEMENT_ADD = "group.announcement.add";
    /** 搜索群 / 加入群 */
    public static final String GROUP_SEARCH = "group.search";
    public static final String GROUP_SEARCH_RESP = "group.search.resp";
    public static final String GROUP_JOIN = "group.join";
    /** 入群审批 / 群管理 */
    public static final String GROUP_JOIN_RESP = "group.join.resp";
    public static final String GROUP_JOIN_REQUESTS = "group.join.requests";
    public static final String GROUP_JOIN_REQUESTS_RESP = "group.join.requests.resp";
    public static final String GROUP_JOIN_HANDLE = "group.join.handle";
    public static final String GROUP_JOIN_REQUEST_PUSH = "group.join.request.push";
    public static final String GROUP_JOIN_RESULT_PUSH = "group.join.result.push";
    public static final String GROUP_ADMIN_ADD = "group.admin.add";
    public static final String GROUP_ADMIN_REMOVE = "group.admin.remove";
    public static final String GROUP_DISSOLVE = "group.dissolve";
    /** 群资料变更推送给群成员 */
    public static final String GROUP_UPDATED_PUSH = "group.updated.push";
    /** 你已被移出群（或被解散），客户端收到后应移除该会话 */
    public static final String GROUP_REMOVED_PUSH = "group.removed.push";
    /** 退群 */
    public static final String GROUP_QUIT = "group.quit";
    public static final String GROUP_QUIT_RESP = "group.quit.resp";

    /** QC 空间（说说） */
    public static final String MOMENT_PUBLISH = "moment.publish";
    public static final String MOMENT_LIST = "moment.list";
    public static final String MOMENT_LIKE = "moment.like";
    public static final String MOMENT_DELETE = "moment.delete";
    public static final String MOMENT_LIST_RESP = "moment.list.resp";
    public static final String MOMENT_OK_RESP = "moment.ok.resp";
    /** 评论：新增 / 列表 / 删除 */
    public static final String MOMENT_COMMENT_ADD = "moment.comment.add";
    public static final String MOMENT_COMMENT_LIST = "moment.comment.list";
    public static final String MOMENT_COMMENT_DELETE = "moment.comment.delete";
    public static final String MOMENT_COMMENTS_RESP = "moment.comments.resp";
    /** 编辑 / 转发 / 点赞列表 / 相册 / 统计 */
    public static final String MOMENT_EDIT = "moment.edit";
    public static final String MOMENT_SHARE = "moment.share";
    public static final String MOMENT_LIKES = "moment.likes";
    public static final String MOMENT_LIKES_RESP = "moment.likes.resp";
    public static final String MOMENT_MEDIA = "moment.media";
    public static final String MOMENT_MEDIA_RESP = "moment.media.resp";
    public static final String MOMENT_STATS = "moment.stats";
    public static final String MOMENT_STATS_RESP = "moment.stats.resp";
    public static final String MOMENT_EDITED_PUSH = "moment.edited.push";
    public static final String MOMENT_SHARED_PUSH = "moment.shared.push";
    /** 别人赞了/评论了我的动态（推给作者） */
    public static final String MOMENT_LIKED_PUSH = "moment.liked.push";
    public static final String MOMENT_COMMENT_PUSH = "moment.comment.push";

    /** 群管理：邀请 / 踢人 / 转让 / 精华消息 */
    public static final String GROUP_INVITE = "group.invite";
    public static final String GROUP_KICK = "group.kick";
    public static final String GROUP_TRANSFER = "group.transfer";
    public static final String GROUP_ESSENCE_ADD = "group.essence.add";
    public static final String GROUP_ESSENCE_REMOVE = "group.essence.remove";
    public static final String GROUP_ESSENCE_LIST = "group.essence.list";
    public static final String GROUP_ESSENCE_LIST_RESP = "group.essence.list.resp";

    /** 音视频通话信令（只做呼叫控制，不带媒体流） */
    public static final String CALL_INVITE = "call.invite";
    public static final String CALL_ACCEPT = "call.accept";
    public static final String CALL_REJECT = "call.reject";
    public static final String CALL_HANGUP = "call.hangup";
    /** 通话音频帧（客户端 → 服务端中转） */
    public static final String CALL_AUDIO = "call.audio";
    /** 通话音频帧（服务端 → 对端） */
    public static final String CALL_AUDIO_PUSH = "call.audio.push";
    public static final String CALL_INCOMING = "call.incoming";
    /** 呼叫已发出（主叫收到，携带服务端分配的 callId） */
    public static final String CALL_INVITED = "call.invited";
    public static final String CALL_ANSWERED = "call.answered";
    public static final String CALL_REJECTED = "call.rejected";
    public static final String CALL_ENDED = "call.ended";
    /** 群通话：参与者列表（含已接听者头像） */
    public static final String CALL_PARTICIPANTS = "call.participants";

    /** 通用错误响应 */
    public static final String ERROR = "error";

    private Cmd() {
    }
}
