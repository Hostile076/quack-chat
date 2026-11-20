# 通信协议

传输层：**WebSocket 文本帧**，端点 `ws://<host>:8080/ws`。
应用层：**JSON 信封 + 命令字**，不依赖具体序列化框架（换成 Protobuf 时只改 `JsonCodec`）。

## 1. 身份体系

| 概念 | 规则 | 例子 |
|---|---|---|
| **QC 号** | 账号唯一标识，从 `10001` 起顺序颁发，`10001` 是内置测试号 | `10001` |
| 用户名 | 登录名，**允许重复**；只能用于搜索和“唯一时登录” | `alice` |
| **群号** | `q` + 数字，从 `10001` 起顺序颁发 | `q10001` |

> 因为用户名可能重名，**登录优先用 QC 号**。用重名用户名登录会返回 409，提示改用 QC 号。

## 2. 信封格式

```json
{
  "cmd":  "msg.send",
  "seq":  12,
  "code": 0,
  "msg":  null,
  "data": {"to":"10002","chatType":"PRIVATE","content":"hi"}
}
```

| 字段 | 类型 | 说明 |
|---|---|---|
| `cmd` | string | 命令字 |
| `seq` | long | 客户端自增序号，响应原样带回；服务端推送为 `0` |
| `code` | int | `0` 成功，非 0 为错误码 |
| `msg` | string | 错误描述 |
| `data` | object | 业务负载，**直接内联为 JSON**；解析同时兼容旧版的“转义 JSON 字符串” |

## 3. 命令表

### 客户端 → 服务端

| 命令 | data（请求体） | 成功响应 | 说明 |
|---|---|---|---|
| `login` | `LoginReq{account, password, deviceId}` | `login.resp{user, friends, groups, token, unread}` | `account` 填 QC 号或用户名；`deviceId` 缺省按 `default`；`token` 用于 HTTP 上传；`unread` 是会话 key → 未读数 |
| `register` | `RegisterReq{username, password}` | `register.resp` | 只填用户名和密码；QC 号由服务端分配，不接受客户端指定 |
| `heartbeat` | `null` | `heartbeat.resp` | 25s 一次 |
| `msg.send` | `SendMessageReq{clientMsgId, to, chatType, msgType, content}` | `msg.ack{messageId, clientMsgId, ts, seq}` | `to` 是 QC 号或群号；同一 `clientMsgId` 重发只落库一次 |
| `msg.read` | `ReadReq{peerId, chatType, ts, seq}` | `msg.read.resp{convKey, unread}` | 已读位点，优先 `seq`；只传 `ts` 时服务端会换算成 seq；回包带该会话剩余未读数 |
| `msg.recv` | `RecvReq{peerId, chatType, seq}` | 无 | 批量 RECVACK：应用完推送后上报，推进该设备的离线游标 |
| `msg.recall` | `RecallReq{messageId}` | `msg.recall{...ChatMessage}` | 只能撤自己发的、2 分钟内；否则 403 |
| `msg.edit` | `EditReq{messageId, content}` | `msg.edit{...ChatMessage}` | 只能编辑自己的文本消息；否则 403 |
| `history.req` | `HistoryReq{peerId, chatType, beforeTs, limit}` | `history.resp` | 按时间戳翻页，兼容旧客户端 |
| `sync.req` | `SyncReq{peerId, chatType, sinceSeq, limit}` | `sync.resp{messages, latestSeq, hasMore}` | **增量同步**：只返回 seq > sinceSeq 的消息；`sinceSeq=0` 返回最近 N 条 |
| `friend.search` | `UserSearchReq{keyword}` | `friend.search.resp` | 按 QC 号或用户名搜索 |
| `friend.request` | `FriendRequestReq{toId, message}` | `friend.request.resp` | 发起好友申请 |
| `friend.requests` | `null` | `friend.requests.resp` | 收到的 + 发出的申请 |
| `friend.handle` | `FriendHandleReq{requestId, accept}` | `friend.handle.resp` | 同意/拒绝 |
| `friend.remark` | `FriendRemarkReq{friendId, remark}` | `friend.remark.resp` | 设置/清空好友备注（只影响自己这一侧） |
| `user.profile` | `ProfileReq{qcId}` | `user.profile.resp` | 不传 qcId 就是看自己 |
| `user.update` | `UpdateProfileReq{username, nickname, avatar, signature, gender, birthday, region, identity}` | `user.update.resp` | 改自己的资料（**不含 QC 号**，QC 号不可修改） |
| `user.like` | `LikeReq{qcId}` | `user.like.resp` | 给资料页点赞 |
| `group.detail` | `GroupDetailReq{groupId}` | `group.detail.resp` | 群名/群号/公告/成员 |
| `group.update` | `GroupUpdateReq{groupId, name, announcement, avatar}` | `group.update.resp` | 仅群主；`avatar` 为空串表示清空 |
| `group.alias` | `GroupAliasReq{groupId, alias}` | `group.update.resp` | 设置我在本群的昵称，空串清除 |
| `group.announcement.add` | `GroupAnnouncementReq{groupId, text}` | `group.update.resp` | 群主发布一条新公告（可多条，新公告在最前） |
| `group.search` | `GroupSearchReq{keyword}` | `group.search.resp{groups[ChatGroup]}` | 按群号/群名称搜索（最多 50 条） |
| `group.join` | `GroupJoinReq{groupId}` | `GroupJoinRequest` | 提交入群申请（等待群主/管理员审批） |
| `group.join.requests` | 无 | `group.join.requests.resp{requests[]}` | 我发出的 + 我有权限审批的申请 |
| `group.join.handle` | `GroupJoinHandleReq{requestId, accept}` | `group.join.requests.resp` | 群主/管理员同意或拒绝 |
| `group.admin.add/remove` | `GroupAdminReq{groupId, userId}` | `group.update.resp` | 仅群主任命/取消管理员 |
| `group.dissolve` | `GroupDissolveReq{groupId}` | `group.update.resp` | 仅群主解散群聊 |
| `group.quit` | `GroupQuitReq{groupId}` | `group.quit.resp` | 退群，群主退会自动转让 |
| `moment.publish` | `MomentPublishReq{text, media[MomentMedia]}` | `moment.ok.resp{Moment}` | 文字可空，但至少 1 图或 1 视频；最多 9 图或 1 视频 |
| `moment.list` | `MomentListReq{qcId, beforeTs, limit}` | `moment.list.resp{moments, nextBeforeTs, hasMore}` | `qcId` 为空 = 好友动态流；游标分页 |
| `moment.like` | `MomentLikeReq{momentId, liked}` | `moment.ok.resp{Moment}` | 每人唯一；`liked=false` 取消；重复请求不累加 |
| `moment.delete` | `MomentCommentReq{momentId}` | `moment.ok.resp` | 只能删自己的，级联清理点赞/评论 |
| `moment.edit` | `MomentEditReq{momentId, text, visibility}` | `moment.ok.resp{Moment}` | 只能编辑自己的文字/可见范围 |
| `moment.share` | `MomentShareReq{sourceId, text, visibility}` | `moment.ok.resp{Moment}` | 转发，带原动态快照 |
| `moment.likes` | `MomentLikeReq{momentId}` | `moment.likes.resp{users[User]}` | 点赞用户列表 |
| `moment.media` | `MomentListReq{qcId, beforeTs, limit}` | `moment.media.resp{items, nextBeforeTs, hasMore}` | 相册（图片/视频） |
| `moment.stats` | `MomentListReq{qcId}` | `moment.stats.resp{posts, likes, comments, shares, photos, videos}` | 空间统计 |
| `moment.comment.add` | `MomentCommentReq{momentId, text}` | `moment.ok.resp{comments[MomentComment]}` | 评论动态 |
| `moment.comment.list` | `MomentCommentReq{momentId}` | `moment.comments.resp{comments}` | 按时间升序 |
| `moment.comment.delete` | `MomentCommentReq{commentId}` | `moment.ok.resp` | 评论作者或动态主人 |
| `group.invite` | `GroupInviteReq{groupId, userIds[]}` | `group.update.resp` | 群主邀请 |
| `group.kick` / `group.transfer` | `GroupMemberOpReq{groupId, userId}` | `group.update.resp` | 群主移人 / 转让 |
| `group.essence.add/remove/list` | `GroupMemberOpReq{groupId, messageId}` | `group.essence.list.resp` | 精华消息 |
| `call.invite` | `CallReq{toId}` | `call.invited` | 发起通话（一对一或群号）；`callId` 由服务端分配，回包/来电里带回 |
| `call.accept` | `CallReq{callId}` | `call.answered` | 接听 |
| `call.reject` | `CallReq{callId}` | `call.rejected` | 拒绝 |
| `call.hangup` | `CallReq{callId}` | `call.ended` | 挂断 |
| `call.audio` | `CallAudioReq{callId, data}` | 无 | base64 PCM 帧（20ms，640 字节），服务端只转给其它已接听者 |
| `typing` | `TypingNotify{to, chatType}` | 无 | 瞬时状态；群聊会校验发送者是否在群里 |

### 服务端 → 客户端（推送，`seq = 0`）

| 命令 | data | 触发时机 |
|---|---|---|
| `msg.push` | `ChatMessage`（含 `seq`） | 新消息；登录后按设备游标补发 |
| `msg.recalled` | `ChatMessage`（SYSTEM，「消息已撤回」） | 会话内某人撤回了一条消息 |
| `msg.edited` | `ChatMessage`（`edited=true`） | 会话内某人编辑了一条消息 |
| `unread.push` | `UnreadPush{convKey, unread}` | 已读后同步同账号其它设备的未读角标 |
| `moment.liked.push` | `Moment` | 有人赞了你的动态 |
| `moment.comment.push` | `MomentComment` | 有人评论了你的动态 |
| `moment.edited.push` | `Moment` | 动态被编辑（多端刷新） |
| `moment.shared.push` | `Moment` | 有人转发了你的动态 |
| `msg.read.push` | `ReadNotify{chatType, peerId, readerId, ts, seq}` | 对方/群成员上报已读，`seq` 为权威位点 |
| `presence.push` | `PresenceNotify{userId, online}` | 好友上下线 |
| `friend.request.push` | `FriendRequest` | 有人申请加你 |
| `friend.handled.push` | `FriendRequest` | 你发出的申请被**拒绝** |
| `friend.added` | `Friend` | 好友关系建立（对方同意，或你同意时对方收到） |
| `friend.updated` | `User` | 好友改了昵称/头像（只推给好友，不广播全服） |
| `friend.remark.push` | `Friend` | 备注变更（多端同步） |
| `group.updated.push` | `GroupDetail` | 群资料/成员/群主变化 |
| `group.removed.push` | `GroupRemoved{groupId, reason}` | 你被移出群；客户端应移除该会话 |
| `group.join.request.push` | `GroupJoinRequest` | 有人申请入群（推给群主/管理员） |
| `group.join.result.push` | `GroupJoinRequest` | 入群申请被同意/拒绝 |
| `call.incoming` | `CallSignal` | 有人呼叫你 |
| `call.answered` / `call.rejected` / `call.ended` | `CallSignal` | 通话状态变化 |
| `call.participants` | `CallParticipants{callId, groupId, members[]}` | 群通话已接听者列表变化 |
| `call.audio.push` | `CallAudioReq{callId, data}` | 服务端转发的音频帧 |
| `typing` | `TypingNotify` | 对方正在输入 |
| `error` | 无（看 `code` / `msg`） | 参数错误、未登录、无权限等 |

### 错误码

| code | 含义 |
|---|---|
| 400 | 参数错误 |
| 401 | 未登录 / 账号密码错误 / 会话被顶下线 |
| 403 | 无权限（不是好友、不在群里） |
| 404 | 未知命令 / 用户不存在 / 申请不存在 |
| 409 | 冲突（用户名重名需改用 QC 号、重复申请、已发过申请） |
| 500 | 服务端内部错误 |
| 4001 | WebSocket 关闭码：同一设备在别处重新登录 |
| 429 | 限流：登录失败过多（10 次/5 分钟/IP）或单连接发消息过快（50 条/秒） |

## 4. 关键流程

### 4.1 登录 + 离线消息补发

```
客户端                                服务端
  |-- WS 握手 /ws ---------------------->|
  |-- {"cmd":"login","seq":1,data:{...}}->|  认证(account=QC号或用户名) -> 绑定会话 -> 广播上线
  |<-- {"cmd":"login.resp","seq":1,...}--|  带 user/friends/groups（含 QC 号、群号）
  |<-- {"cmd":"msg.push","seq":0,...}----|  离线消息 1
```

顺序很重要：**先回 `login.resp`，再推离线消息**。

### 4.2 好友申请

```
A                               服务端                              B
|-- friend.search -------------->|                                 |
|<-- friend.search.resp ---------|   QC 号优先、其余按 QC 升序        |
|-- friend.request{toId} ------->|                                  |
|<-- friend.request.resp --------|---- friend.request.push -------->|  B 在线才推
|                               |   （B 离线则存着，登录后 friend.requests 拉取）
|                               |<------------ friend.handle{accept} |
|<-- friend.added(Friend) -------|<-- friend.handle.resp(newFriend) -|  B 同意
|                               |   拒绝时：friend.handled.push ---> A
```

### 4.3 搜索排序规则

- 关键字是**纯数字**：先精确匹配 QC 号（排第一），再匹配“用户名恰好等于这串数字”的账号；
- 关键字**不是数字**：按用户名匹配（精确优先，其次包含）；
- 结果统一**按 QC 号升序**，并排除自己。

### 4.4 心跳与断线

- 客户端每 **25s** 发 `heartbeat`，服务端 `idle timeout = 120s`。
- 异常断开后按 3/6/9… 秒退避重连，最多 5 次；收到 **4001** 不重连（避免两端互相踢）。

### 4.5 会话内序号（seq）

每个会话（私聊双向同一个、群聊按群号）维护一个从 1 开始自增的 `seq`：

- 服务端落库时分配，`ChatMessage.seq` 与 `msg.ack.seq` 都带它；
- 会话内消息**按 seq 排序**，时间戳只用于展示（同一毫秒多条、时钟回拨都不会乱序）；
- 已读位点用 seq 表达，客户端上报 `ReadReq.seq`，服务端推送 `ReadNotify.seq`；
- 私聊与群聊、不同群之间的 seq 互相独立，没有全局顺序。

### 4.6 增量同步与离线补发

服务端不再“每个离线用户每条消息存一份副本”，而是给**每台设备**在每个会话记一个**已收到 seq**游标：

- 在线投递 → 设备连接收到推送，客户端应用后发 `msg.recv` 才推进游标（RECVACK）；
- 离线 → 什么都不存；
- 登录时按该设备游标把缺口一次性推给客户端（每个会话最多 100 条，没推完下次继续）；
- 客户端打开会话时发 `sync.req{sinceSeq=本地最大 seq}` 补洞；`sinceSeq=0` 表示“给我最近 N 条”。

因此：`msg.ack` 成功 = 服务端已提交；对方是否收到要看该设备的游标/同步结果，两者不要混为一谈。
同一账号可以多台设备同时在线；只有**同一 deviceId** 重新登录才会以 4001 踢掉旧连接。

### 4.7 三态回执

| 状态 | 判定 |
|---|---|
| 发送中 | 客户端本地消息还没收到 `msg.ack` |
| 已送达 | 收到 `msg.ack`（服务端已提交），但对方还没上报已读 |
| 已读 | 对方的已读 seq ≥ 这条消息的 seq（群聊为“已读 N”） |

### 4.7 消息类型

| msgType | content 格式 |
|---|---|
| `TEXT` | 纯文本 |
| `IMAGE` | 图片 URL |
| `FILE` | `url\|文件名\|大小` |
| `VIDEO` | `视频URL\|封面URL\|时长ms\|宽\|高\|文件名` |
| `GIFT` | `giftId\|名称\|图标` |
| `SYSTEM` | 系统提示文本 |

视频下载接口支持 `Range`，返回 `206 + Content-Range`，播放器可以拖动进度。
上传上限 300MB（服务端 multipart 与客户端流式上传都限制在 300MB）。

### 4.8 编辑 / 撤回 / 未读

- **编辑**：`msg.edit` 只允许作者改自己的 TEXT 消息，服务端生成 `edited=true` 的新消息并广播 `msg.edited`；
  历史同步/本地库都按新内容保存。
- **撤回**：2 分钟内、只能撤自己的；消息不删除（避免 seq 空洞），替换成 SYSTEM「消息已撤回」并广播 `msg.recalled`；
  如果这条还没被对方读，服务端未读数会减一。
- **未读**：服务端按用户+会话存权威未读数，登录时随 `login.resp` 下发；`msg.read` 到达后清零/重算，
  并给同账号所有设备推 `unread.push`。

## 5. 文件上传（HTTP，不走 WebSocket）

| 接口 | 说明 |
|---|---|
| `POST /api/token` | JSON `{account, password, deviceId}`；返回 `{token}` | 上传令牌失效时刷新（不重建 WS 会话） |
| `POST /api/upload` | multipart 字段 `file` + `Authorization: Bearer <token>`；返回 `{id, name, size, url}`；上限 **300MB**（流式）；无 token 返回 401 |
| `GET /api/files/{id}?exp=..&sig=..` | 下载/预览；`sig = HMAC-SHA256(id:exp, data/file-signing.key)`，篡改/过期返回 403；历史无签名 URL 仍兼容 |

消息里只存 URL：图片 `content = url`，文件 `content = url|文件名|大小|发送方本地路径`。文件保留 7 天，
下载会刷新 mtime；落盘名是 32 位 hex（无扩展名），下载时按 magic bytes 判图片：图片 `inline`，
其它类型强制 `attachment` + `X-Content-Type-Options: nosniff`。

## 6. 运维接口

| 接口 | 说明 |
|---|---|
| `GET /healthz` | `{status, uptimeSeconds, online}`，给部署/容器探针用 |
| `GET /metrics` | Prometheus 文本：在线连接、接收消息数、补发消息数、内存消息数、运行时长 |

## 7. 待扩展

`msg.edit`（编辑）、`rtc.*`（真正的 WebRTC 音视频）、
大群读扩散的分页订阅者、TLS 终止（部署层）。
新增命令只需要：**加一个 `Cmd` 常量 + 一个 `CommandHandler` 实现类**。

## 8. 客户端实现备注

- `msg.read` 现在有 `msg.read.resp` 回包（带剩余未读数），旧客户端用 `WsClient.send` 忽略它也不受影响；`msg.recv`、`typing`、`call.audio` 仍是**单向上报**，不回响应。
- 同一账号允许多设备；同一 deviceId 重登会以 4001 踢掉旧连接，被踢端回登录页、不自动重连。
- 客户端用 SQLite 缓存每个账号的消息/lastSeq/草稿/已读；启动后先渲染本地，再用 `sync.req` 补缺口。
