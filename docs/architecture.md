# 架构设计

## 1. 全景

```
┌──────────────────────────┐            ┌──────────────────────────┐
│   JavaFX 客户端 A         │            │   JavaFX 客户端 B         │
│  ┌────────────────────┐  │            │  ┌────────────────────┐  │
│  │ ui/  登录 / 主界面  │  │            │  │ ui/                │  │
│  ├────────────────────┤  │            │  ├────────────────────┤  │
│  │ store/ 会话与未读   │  │            │  │ store/             │  │
│  ├────────────────────┤  │            │  ├────────────────────┤  │
│  │ net/WsClient       │  │            │  │ net/WsClient       │  │
│  └─────────┬──────────┘  │            │  └─────────┬──────────┘  │
└────────────┼─────────────┘            └────────────┼─────────────┘
             │  WebSocket (JSON 信封)                 │
             └───────────────┬───────────────────────┘
                             ▼
┌───────────────────────────────────────────────────────────────┐
│                     chat-server (Spring Boot)                 │
│                                                               │
│  ws/ChatWebSocketHandler   接入层：解析、鉴权、异常兜底         │
│           │                                                   │
│  dispatch/CommandDispatcher   命令路由（cmd -> handler）        │
│           │                                                   │
│  dispatch/handler/*        业务编排：登录、发消息、历史、typing   │
│           │                                                   │
│  service/*                 领域服务：用户/好友/群/消息/在线状态   │
│           │                                                   │
│  store/*                   存储：消息环形缓冲、离线队列           │
│  ws/SessionManager         路由：userId -> 连接                │
└───────────────────────────────────────────────────────────────┘
```

## 2. 模块职责

| 模块 | 职责 | 依赖 |
|---|---|---|
| `chat-common` | 协议信封、命令字、DTO、JSON 编解码 | 仅 Jackson |
| `chat-server` | 长连接接入、消息路由、业务逻辑、内存态 + SQLite 快照持久化 | common + Spring Boot |
| `chat-client` | 桌面 UI、WebSocket 客户端、本地 SQLite 缓存（消息/游标/草稿） | common + JavaFX |

**依赖方向是单向的**：client / server 都依赖 common，common 不依赖任何一端。
这样协议改动只有一处，两端不会漂移。

## 3. 服务端分层与扩展点

```
接入层  ChatWebSocketHandler      ← 只做协议层的事，不含业务
  ↓
分发层  CommandDispatcher         ← Map<cmd, handler>，新增命令零改动
  ↓
业务层  *Handler / *Service       ← 事务边界、权限校验
  ↓
存储层  *Store / *Repository      ← 内存态 + PersistenceService 定时落 SQLite（未来换 MySQL / Redis）
```

**扩展点的实际用法**：

- 加“撤回消息”：新增 `RecallHandler implements CommandHandler`，`cmd()` 返回 `"msg.recall"`。不用改分发器。
- 换数据库：`UserService` / `FriendService` / `MessageStore` 三个类的内部实现换成 Mapper，接口签名不变。
- 换传输层：`ChatWebSocketHandler` 换成 Netty 的 `ChannelInboundHandler`，`CommandDispatcher` 以下全部复用。

## 4. 关键设计决策

### 4.1 为什么用 JSON 文本帧，而不是自定义二进制协议？

QQ 原生协议是二进制的（省流量、解析快），但那套东西的复杂度（TLV、序列化、版本兼容）会吃掉你 80% 的精力，
而且调试困难（抓包看不懂）。

JSON 的代价是包体大约 2~3 倍、解析慢一个量级 —— 对文字聊天（每秒几条）完全无所谓。
**先跑通再优化**：等你要做图片/语音/批量同步时，再针对大包换二进制，协议信封不用动。

### 4.2 为什么服务端用 Spring WebSocket，而不是直接上 Netty？

| | Spring WebSocket（当前） | 裸 Netty |
|---|---|---|
| 上手成本 | 低，注解 + 一个 Handler | 高，要自己管 pipeline、编解码、心跳 |
| 单机连接数 | 万级（Tomcat NIO） | 十万级 |
| 线程模型 | 每连接不一定绑线程，但发送需串行化 | 可精确控制 EventLoop |
| 适合阶段 | MVP、毕设、内部项目 | 真要扛量 |

骨架阶段选 Spring，是因为**你现在的瓶颈是“把功能做出来”，不是“扛 10 万连接”**。
真到了要换的时候，业务层代码可以整体复用（见 3. 扩展点）。

### 4.3 多端登录与连接归属

`SessionManager` 的路由表是 `userId -> deviceId -> ChatSession`：同一账号允许多台设备同时在线，
每台设备有独立的连接与离线游标。**同一 deviceId** 再登录会覆盖并 `close(4001)` 旧连接（“这台设备重新登录”），
不影响该账号的其它设备。

这里有两个容易写错的细节：

1. 被顶掉的旧连接随后也会触发 `afterConnectionClosed`，如果无条件广播“下线”，
   会把刚上线的新连接误标成离线。`close()` 用 `remove(deviceId, session)` 判断“我是不是当前那条连接”。
2. 只有**最后一台设备**断开时才广播离线（`CloseResult.lastDevice`）；关掉手机不应该让电脑也变成离线。

登录时 `deviceId` 缺省为 `default`，所以旧客户端仍然是“单端登录”的行为。

### 4.4 先落库再投递

`MessageService.send()` 的顺序是：校验 → 落库 → 投递 → 回 ACK。

反过来的话，对方可能收到一条“查不到历史”的消息（投递快于落库）。
先落库再投递，即使投递失败也只是延迟（离线补发兜底）。

### 4.5 消息不丢、不重

| 问题 | 手段 |
|---|---|
| 不丢 | 每台设备每个会话记「已收到 seq」游标；客户端 RECVACK（`msg.recv`）后才推进；登录按缺口补发 |
| 不重 | `clientMsgId` 幂等（内存 LRU + 客户端按 id/seq 去重）；DB `(conv_key, seq)` 唯一索引 |
| 有序 | 会话内自增 `seq`（私聊双向同 key、群聊按群号），时间戳只用于展示 |
| 可恢复 | 重装/换设备只需知道 `lastSeq`，`sync.req(sinceSeq)` 只返回缺口；客户端 SQLite 持久化 lastSeq |

> 服务端游标是“已经发给这个用户的最大 seq”；客户端本地游标要等 P3 本地库才持久化。
> 现阶段的取舍写在 README「本轮优化」的“未做”里。

### 4.6 持久化：元数据快照 + 消息追加

元数据（账号/资料/好友/群/已读/离线游标）在内存里，`PersistenceService` 每 2 秒对**元数据**算一次
**稳定序指纹**（先排序再 hash，避免 ConcurrentHashMap 迭代顺序抖动导致误判），有变化才在一个事务里
「清元数据表 + 重写」；**消息表只追加**：每条消息带 `(conv_key, seq)`，写库时只 `INSERT OR IGNORE`
还没写过的消息。退出时再补一次。

- 优点：元数据不会漏表；消息写放大从 O(历史总量) 降到 O(新增条数)；事务保证断电停在上一个完整状态。
- 代价：元数据仍是全量重写（数据量小可接受）；消息表会一直增长，需要定期归档/清理策略。
- 额外状态：离线游标（按设备）、未读数（按用户+会话）各自单独落表，变化时才重写。
- 已知未落库：精华消息、说说、通话状态——重启即丢；这些是下一步要补的表。

## 5. 水平扩展路线（单机 → 集群）

单机版有一个硬限制：`SessionManager` 在 JVM 内存里，用户连在 A 节点，B 节点找不到他。

```
阶段二：无状态接入 + 共享路由
  客户端 --WS--> Nginx/SLB --> gateway-1 / gateway-2 ...
                                  │
                      Redis: userId -> nodeId（带 TTL 与心跳续期）
                                  │
                      MQ（Kafka/RocketMQ）：跨节点投递消息
                                  │
                      MySQL：消息与关系落库
```

要点：
1. **网关层不存业务状态**，只做连接与转发；
2. **路由表放 Redis**，节点下线靠 TTL 自动过期 + 重连重绑；
3. **跨节点投递走 MQ**，消费端按 `toUserId` 查找本地连接，找不到就写离线；
4. **群消息扇出**：小群写扩散（逐条投递），大群改读扩散（只存一份，成员拉取）。

## 6. 安全清单（上线前逐项过）

- [ ] 传输：`wss://` + TLS，禁止明文
- [ ] 密码：BCrypt / Argon2（当前 SHA-256 是教学简化）
- [ ] 登录：失败次数限制、验证码、异地登录提醒
- [ ] 鉴权：每条命令都要校验“我是否有权访问这个会话”（当前已校验好友/群成员）
- [ ] 注入：所有消息走参数化 SQL；富文本要过滤（防 XSS，客户端渲染链接要白名单）
- [ ] 限流：单连接消息速率、单 IP 连接数
- [ ] 内容安全：敏感词、图片审核（合规要求）
- [ ] 日志：不打印密码、token、完整消息体（脱敏）

## 7. 性能与压测建议

想在简历/答辩上讲出数字，可以按这个顺序做：

1. 用 JMeter / Gatling 开 1000 个 WebSocket 连接，每连接 1 msg/s，观察 P99 延迟与内存；
2. 关掉日志、加大 Tomcat 线程池、调 `maxTextMessageBufferSize`，对比吞吐；
3. 把 `ChatSession.send()` 的 `synchronized` 换成“每连接单写线程 + 有界队列”，看抖动变化；
4. 用 `jstack` 抓一次阻塞点，确认没有在 EventLoop 里做 IO。

## 8. 代码地图

| 想改什么 | 去哪个文件 |
|---|---|
| 加一个协议命令 | `chat-common/protocol/Cmd.java` + 新建 `dispatch/handler/XxxHandler.java` |
| 改消息存储 | `chat-server/store/MessageStore.java` |
| 改在线状态策略 | `chat-server/service/PresenceService.java` |
| 改会话路由（单机→集群） | `chat-server/ws/SessionManager.java` |
| 改登录界面 | `chat-client/ui/LoginView.java` + `app.css` |
| 改气泡样式 | `chat-client/ui/MessageCell.java` + `app.css` 的 `.bubble-*` |
| 改重连策略 | `chat-client/AppContext.java` 的 `scheduleReconnect()` |
| 改数据目录位置 | `chat-common/util/AppDirs.java` |
| 改打包方式 | `packaging/build-exe.ps1` |

## 9. 打包与运行形态

发布形态是 **jpackage app-image**：一个绿色目录，里面是 `.exe` + 裁剪过的 JRE + 依赖 jar。
目标机器不需要装 Java，也不需要管理员权限。

```
dist/QuackChat/
├── QuackChat.exe          # 启动器（自带图标、版本信息）
├── app/                   # 我们的 jar + 依赖 + JavaFX
├── runtime/               # 裁剪后的 JRE
└── data/                  # 运行期数据（临时文件、JavaFX 缓存）
```

三个必须记住的约束（都在代码里处理了）：

1. **JavaFX 走 classpath，不走模块路径**。被 `--module-path` 链进运行时镜像的 JavaFX 缺少原生库，
   启动即 `No toolkit found`；classpath 方式下 JavaFX 会自己把 DLL 解压到缓存目录。
2. **入口类不能是 `Application` 子类**。JDK 启动器会对这种主类强制要求 JavaFX 模块，
   所以用普通类 `io.quack.client.Launcher` 引导，内部再 `Application.launch(...)`。
3. **所有可写目录必须在启动参数里指定**。`java.io.tmpdir` 在 JVM 启动时被缓存，
   运行期 `System.setProperty` 无效；因此在 `--java-options` 里写死 `$APPDIR\..\data\tmp`。
   数据目录的解析优先级见 `AppDirs`：显式参数 → exe 旁的 `data/` → `~/.quack-chat` → 系统临时目录。

服务端额外做了一件事：给 `TomcatServletWebServerFactory` 设置 `baseDirectory`，
绕开 Spring Boot “没有 basedir 就创建临时目录”的分支（`ServerStorageConfig`），
这样即使宿主机的临时目录不可写也能启动。
