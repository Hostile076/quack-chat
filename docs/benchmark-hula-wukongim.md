# 对标分析：HuLa 与 WuKongIM 对 Quack Chat 的启发

> 阅读范围：两个仓库的 README、WuKongIM 的架构 / 消息链路 / Channel 文档、HuLa 的工程指南、
> `package.json`、`src/services/wsType.ts`、`src/services/webSocketRust.ts`，以及 HuLa-Server 的 README。
> 没有逐行通读两个仓库的全部源码，因此下文对它们的描述以官方文档与上述源码为准；
> 对 Quack Chat 的判断基于本仓库的实际代码。

## 0. 结论先行

1. **两者不是同一类项目**：HuLa 是"一个可交付的 IM 产品"（客户端 + 微服务后端），
   WuKongIM 是"一个可嵌入的 IM 内核"（服务端 + 多语言 SDK，产品逻辑留给业务方）。
   Quack Chat 现在是"单体版两者合体"：自己做客户端、自己做服务端、自己存数据。
2. **Quack Chat 的短板不在功能数量，而在消息语义**。用 `ts` 排序/翻页、每会话 500 条环形缓冲、
   内存离线队列、群消息逐人写扩散——这四条决定了它只能停留在小团队演示，无法长大。
   WuKongIM 最值钱的不是 Raft，而是它把 **channel / seq / commit / ack** 这套语义先定死了。
3. **HuLa 最值钱的是"产品工程"**：客户端本地 SQLite + 迁移、断线重连状态机、托盘/多窗口/自动更新/i18n、
   biome + husky + vitest + commitlint。这些决定"像不像一个能交付的软件"，恰好是 Quack Chat 目前最薄的一层。
4. **两者的共同点是"状态收敛"**：谁对哪类状态有权威、失败后怎么补偿。IM 的难点在这里，
   不在气泡圆角和 240Hz。Quack Chat 已经把 UI 打磨得很细，下一阶段的预算应该挪到状态机与持久化。
5. **不要照搬**：HuLa-Server 的 Spring Cloud 全家桶、WuKongIM 的 Multi-Raft/ISR，对单人/小团队项目都是负资产。
   正确做法是"借语义、不借规模"。

## 1. 两个项目分别在解决什么问题

| 维度 | HuLa | WuKongIM | Quack Chat（现状） |
|---|---|---|---|
| 定位 | 完整 IM 产品（客户端 + 服务端） | IM 通信内核 + SDK | 完整 IM 产品（单体、自包含） |
| 客户端 | Tauri 2 + Vue 3 + Pinia + TS；Rust 侧持有 WebSocket | 提供 JS/Android/iOS/Flutter 等 SDK，不含 UI | JavaFX 21，JDK WebSocket |
| 服务端 | Spring Cloud 微服务：gateway / oauth / im / ws / presence / ai / base / system；Netty + WebFlux | 单个 Go 二进制，内置存储与集群；Controller / Slot / Channel / Gateway / Transport | Spring Boot 单体，内存态 + SQLite 快照 |
| 存储 | MySQL + Redis + RocketMQ | 自带存储（消息日志、元数据 Raft），不依赖外部 DB/缓存/MQ | SQLite 单文件 + 内存 Map |
| 消息模型 | 消息落库 → PushService 查路由 → WS 节点 → 客户端 ACK → 更新已送达 | 频道有序日志 + messageSeq + clientMsgNo + SENDACK（quorum commit）+ 客户端按 seq 同步 | 内存 Map + 时间戳排序 + 离线队列 + ACK |
| 多端 | 多设备登录管理 | 多设备会话 + 每设备游标 | 单端登录（4001 踢下线） |
| 集群 | 微服务横向扩展 + 路由表 | 256 hash slot + Channel Leader/ISR | 单机 |
| 运维 | 未强调 | Manager、指标、诊断、备份、压测 CLI | 日志 |

一句话：**HuLa 在回答"产品怎么长"，WuKongIM 在回答"消息怎么不丢"**。

## 2. 最值得借鉴的七件事（按投入产出排序）

### 2.1 把"已提交"和"已送达""已读"分开（WuKongIM）

WuKongIM 的文档写得很直白：SENDACK 成功只表示**频道日志完成了 quorum commit**，
不代表接收者在线、不代表客户端 RECVACK、更不代表已读；投递是提交之后的**副作用**，
投递失败不回滚日志。客户端断线后用稳定的 `client_msg_no` 去查询/同步判断结果，
而不是靠"超时 = 没发出去"。

Quack Chat 现在 `msg.ack` 确实只代表落库，但 UI 上把"最后一句话 + 服务端已读位点"
直接标成「已读 / 未读」，用户会以为是"对方看到了"。建议：

- 显式三态：`已发送（本地）→ 已送达（服务端收到并投递）→ 已读（对方上报位点）`；
- 服务端在 ACK 里区分 `committed` 与 `delivered`（至少加字段），客户端据此渲染；
- 断线重发用 `clientMsgId` 查询结果，而不是盲目重发。

**收益**：消除"发出去显示已读但对方没收到"这类最伤信任的问题。**成本**：协议加字段 + UI 三态。

### 2.2 用"会话内自增 seq"替代"时间戳"（WuKongIM，最关键的一条）

WuKongIM 的 Channel 就是一个**有序日志**：同一频道按日志序号可见，不同频道并行、无全局顺序；
历史/最新消息读取都受已提交 HW 约束。客户端按 `seq` 做增量同步、已读位点、多端游标。

Quack Chat 现在：

- 历史翻页用 `beforeTs`（时间戳），服务端消息 `ts = System.currentTimeMillis()`；
- 已读位点也是 `ts`；
- 会话缓冲只留最近 500 条，`byId` 无限增长。

时间戳排序在单机低并发下"看起来对"，但一旦出现：同一毫秒多条消息、时钟回拨、
多实例部署、消息重发，就会出现乱序/漏读/已读位点漂移。建议：

- 消息主键里加 `conversationId + seq`（服务端按会话原子自增，落库唯一约束）；
- `history.req` 增加 `afterSeq/beforeSeq`，`ts` 只做展示；
- 已读位点从 `ts` 改为 `seq`；
- 客户端保存 `lastSeq`，登录后走 `sync.req(conversationId, sinceSeq)` 增量补洞。

**收益**：顺序、去重、增量同步、多端游标全部一次性解决，是后面所有能力的地基。
**成本**：服务端消息表 + 协议 + 客户端 store 都要动，是本次改造里最大的一块。

### 2.3 幂等键必须落库，而不是内存 LRU（WuKongIM + 我们已做的一半）

WuKongIM 幂等的作用域是 `sender + client message number + channel`，在权威 writer 里做，
所以重连重发、跨节点转发都安全。Quack Chat 上一轮刚加了 `MessageStore` 的 LRU 去重，
但它只在内存、重启即失效，而且 `messages` 表没有唯一约束。建议：

- `messages` 表加唯一索引 `(from_id, client_msg_id)`；
- 冲突时返回已存在消息的 ACK，不重复投递；
- 保留时间窗口（比如 7 天）后归档，避免无限增长。

### 2.4 群消息"一份存储 + 读扩散"，而不是逐人写离线（WuKongIM）

WuKongIM 的大群：频道日志**只保存一份**提交消息，提交后分页读取订阅者、生成有界投递计划，
不会为十万成员复制十万份持久记录；"已 commit"和"全员已收到"是两个事件。

Quack Chat 的 `PushService.pushChatMessage` 对每个不在线的群成员调 `offlineStore.push`，
等于**写扩散**：100 人群每人离线 1 天，内存与后续落库都会爆。建议：

- 群消息只存一份（带 seq）；
- 离线不存"每人一条消息副本"，只存"每人一个 lastSeq 缺口"；
- 上线后按 `sync` 拉取，投递失败不影响已提交。

**收益**：群规模从"几十人"到"几千人"不需要改架构。**成本**：离线表模型重写（不大）。

### 2.5 客户端要有本地库和"先渲染本地、再增量同步"（HuLa）

HuLa 客户端用 `tauri-plugin-sql` + `src-tauri/entity` + `migration` 把会话/消息落本地 SQLite，
配 `pinia-plugin-persistedstate`；启动后先读本地，WS 只负责增量。它还做了：
断线重连状态机（DISCONNECTED/CONNECTING/CONNECTED/RECONNECTING/ERROR）、
健康度（lastPong、连续失败次数、RTT）、可配置心跳/重连参数、事件总线 + 监听器统一清理。

Quack Chat 客户端目前**不存聊天记录**：重启后靠 `history.req` 拉 50 条，断网就是空白，
`ui-prefs` 之外没有本地状态。建议（按顺序）：

1. 本地 SQLite（或 H2）存 `conversation/message/read_state/draft`；
2. 启动渲染本地 → 后台 `sync` 补齐；
3. 未读数、已读位点、草稿本地维护；
4. 把 `WsClient` 的心跳/重连参数抽成配置（HuLa 的 `updateConfig` 做法）。

**收益**：断网可用、启动秒开、流量下降。**成本**：客户端要引入 JDBC/SQLite 依赖与迁移脚本。

### 2.6 接入层无状态 + 路由表外置（HuLa-Server / WuKongIM）

HuLa-Server 的 WS 服务维护"节点-设备-用户"指纹级映射，PushService 先查路由表定位目标 WS 节点，
再投递到该节点专属主题，避免全服广播（它们给出的对比是 O(k) vs O(N)）。
WuKongIM 则是 Gateway 只做连接与转发，Presence Authority 决定 owner 节点，跨节点走 owner-push RPC。

Quack Chat 的 `SessionManager` 是两张 JVM 内存 Map，注释里也写了"集群化时换成 Redis"。
如果未来要多实例：把 `SessionRegistry` 抽成接口（本地实现 + Redis 实现），
`sendTo(userId)` 变成"查路由 → 本地直投 / 远端转发"。**不要**现在就把业务拆成微服务。

### 2.7 运维面：至少要有健康检查、指标、消息轨迹（WuKongIM）

WuKongIM 把 Manager、指标、诊断、备份、`wkcli bench` 都做进产品；这解释了他们为什么敢说
"看得见的运维"。Quack Chat 目前只有日志 + 39 个探针。性价比最高的补齐：

- `/healthz`、`/readyz`、`/metrics`（连接数、消息 QPS、投递失败、落库耗时）；
- 一条消息的轨迹查询（msgId → 提交/投递/已读时间点）；
- 把现有 `perf/` 探针收进一个 `verify.ps1`，作为发版前门禁。

## 3. 不建议照搬的四件事

1. **HuLa-Server 的 Spring Cloud 全家桶**：gateway + nacos + oauth + MQ + 多模块，
   对单人项目是部署与调试成本的平方级增加。Quack Chat 的模块边界（common/server/client）已经够用，
   先把语义做对，再谈拆分。
2. **WuKongIM 的 Multi-Raft / ISR / 槽位迁移**：那是"通信内核"级别的复杂度，自研复刻不现实。
   可以借它的**概念**（channel、seq、commit、fence），不要借它的**实现**。
3. **HuLa 客户端的巨型 store + 几十个 `ws-*` 事件名**：`webSocketRust.ts` 里一长串
   `listen('ws-xxx')` 硬编码事件，跨模块耦合明显。Quack Chat 的 `AppContext` 已经是同一种
   God object 味道（1200 行、50+ 方法），继续加功能前应先拆 domain service。
4. **"像大厂"的技术堆叠**：Redis/MQ/分库分表在 DAU 没过万之前都是负债。
   WuKongIM 自己也强调单节点就是"单节点集群"，语义一致比堆组件重要。

## 4. 给 Quack Chat 的演进路线（带验收）

| 阶段 | 目标 | 关键改动 | 验收标准 |
|---|---|---|---|
| **P0 消息语义**（最高优先） | 不丢、不重、有序、可补洞 | `conversationId+seq`、唯一索引 `(channel,seq)`、幂等落库、`sync.req(sinceSeq)`、三态回执 | 断网重连/重发/时钟回拨脚本全过；重装客户端能补全 |
| **P1 存储与离线** | 群规模可长大 | 群消息单副本 + 每人 lastSeq 缺口；离线表落库；消息表增量写（替代全量重写） | 100 人群 10 万条消息，内存/落库耗时可控 |
| **P2 接入与安全** | 可多实例、可公网 | token 鉴权（HTTP + WS）、`SessionRegistry` 接口化、限流 | 双实例 + 网关压测；越权用例全挂掉 |
| **P3 客户端本地库** | 断网可用 | SQLite + 迁移、启动读本地、增量同步、草稿/未读本地化 | 断网可读历史、可写草稿；启动 P95 < 500ms |
| **P4 协议与性能** | 降本增效 | 去掉 `data` 的二次转义、批量 ACK、发送队列 + 背压、指标 | 单机 1000 连接 1 msg/s，P99 可测 |
| **P5 产品化** | 像交付的软件 | 托盘、多窗口、自动更新、i18n、崩溃恢复 | 手工验收清单 |

**如果只做一件事**：P0。理由：P1 的离线模型、P3 的本地同步、P4 的批量 ACK、甚至"已读/未读"
的可信度，全都依赖"会话内 seq"这个地基；没有它，后面每加一个功能都在给时间戳打补丁。

## 5. 三个反直觉的判断

1. **"功能多"不是壁垒，"状态收敛"才是**。Quack Chat 已有 40+ 功能，但一次时钟回拨或一次
   重装就可能让它看起来"丢消息"；用户对 IM 的容忍度全在这一点上。
2. **"发送成功"必须允许"对方没收到"**。WuKongIM 把投递当副作用，Quack Chat 现在把 ACK 当
   "万事大吉"，这在弱网下会转化成"已读但没收到"的信任崩塌。
3. **客户端本地库不是优化，是正确性**。HuLa 的 SQLite 不只是"离线缓存"，它让"我看到的历史"
   不再依赖服务端每次都在线；Quack Chat 的 `history.req` 模式在服务端不可达时等于没有历史。

## 6. 参考来源

- WuKongIM：README（自托管、内置存储与集群）、`docs.githubim.com/zh/server/architecture/`、
  `/message-flow/`、`/channels/`（SENDACK 语义、quorum commit、大群分页 fanout、背压、NoPersist）。
- HuLa：README（功能矩阵、跨平台）、`docs/project_guide.md`（目录、SQLite、migrations、工程命令）、
  `package.json`（Pinia、vue-virtual-scroller、plugin-sql、vitest、biome、husky）、
  `src/services/wsType.ts`（消息类型与业务事件）、`src/services/webSocketRust.ts`（Rust WS 状态机与事件桥）。
- HuLa-Server：README（Spring Cloud + Netty + MySQL/Redis/RocketMQ、消息流程与 ACK、精准路由）。
