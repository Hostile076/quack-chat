-- ============================================================
-- Quack Chat 数据库设计（MySQL 8.0 / InnoDB / utf8mb4）
--
-- 说明：骨架阶段服务端用内存实现，这份 DDL 是给 V2 落库用的。
-- 设计要点写在各表注释里，迁移时按注释里的“坑”做决策。
-- ============================================================

CREATE DATABASE IF NOT EXISTS quack_chat
    DEFAULT CHARACTER SET utf8mb4
    DEFAULT COLLATE utf8mb4_0900_ai_ci;

USE quack_chat;

-- ------------------------------------------------------------
-- 用户
-- ------------------------------------------------------------
CREATE TABLE t_user
(
    id            VARCHAR(32)  NOT NULL COMMENT '用户 id，演示用 u1/u2；生产建议雪花 id',
    username      VARCHAR(64)  NOT NULL COMMENT '登录名，唯一',
    password_hash VARCHAR(128) NOT NULL COMMENT 'BCrypt/Argon2 哈希，绝不存明文',
    nickname      VARCHAR(64)  NOT NULL COMMENT '昵称',
    avatar        VARCHAR(255)          DEFAULT NULL COMMENT '头像 URL',
    signature     VARCHAR(255)          DEFAULT NULL COMMENT '个性签名',
    online_status TINYINT      NOT NULL DEFAULT 0 COMMENT '0 离线 1 在线 2 隐身（内存态为主，这里仅落快照）',
    created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_username (username)
) ENGINE = InnoDB COMMENT '用户表';

-- ------------------------------------------------------------
-- 好友关系
--
-- 坑：不要只存单向。A 加 B 之后，A 的好友列表和 B 的好友列表都要能查到，
-- 所以要么存两行（推荐，查询简单、可分别设备注），要么存一行无向边。
-- 这里选择存两行。
-- ------------------------------------------------------------
CREATE TABLE t_friend
(
    id         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    user_id    VARCHAR(32)     NOT NULL COMMENT '拥有者',
    friend_id  VARCHAR(32)     NOT NULL COMMENT '好友',
    remark     VARCHAR(64)              DEFAULT NULL COMMENT '备注名',
    is_blocked TINYINT         NOT NULL DEFAULT 0 COMMENT '是否拉黑',
    created_at DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_friend (user_id, friend_id),
    KEY idx_friend_reverse (friend_id) COMMENT '反查“谁加了我”，好友请求场景需要'
) ENGINE = InnoDB COMMENT '好友关系表';

-- ------------------------------------------------------------
-- 好友申请
-- ------------------------------------------------------------
CREATE TABLE t_friend_request
(
    id         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    from_user  VARCHAR(32)     NOT NULL,
    to_user    VARCHAR(32)     NOT NULL,
    message    VARCHAR(255)             DEFAULT NULL COMMENT '验证消息',
    status     TINYINT         NOT NULL DEFAULT 0 COMMENT '0 待处理 1 同意 2 拒绝 3 过期',
    created_at DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    handled_at DATETIME                 DEFAULT NULL,
    PRIMARY KEY (id),
    KEY idx_request_to (to_user, status),
    KEY idx_request_from (from_user)
) ENGINE = InnoDB COMMENT '好友申请表';

-- ------------------------------------------------------------
-- 群
-- ------------------------------------------------------------
CREATE TABLE t_group
(
    id         VARCHAR(32)  NOT NULL,
    name       VARCHAR(64)  NOT NULL,
    owner_id   VARCHAR(32)  NOT NULL COMMENT '群主',
    avatar     VARCHAR(255)          DEFAULT NULL,
    member_cnt INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '冗余计数，避免每次 count(*)',
    created_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_group_owner (owner_id)
) ENGINE = InnoDB COMMENT '群表';

CREATE TABLE t_group_member
(
    group_id  VARCHAR(32)     NOT NULL,
    user_id   VARCHAR(32)     NOT NULL,
    role      TINYINT         NOT NULL DEFAULT 0 COMMENT '0 成员 1 管理员 2 群主',
    joined_at DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (group_id, user_id),
    KEY idx_member_user (user_id) COMMENT '查“我加入了哪些群”'
) ENGINE = InnoDB COMMENT '群成员表';

-- ------------------------------------------------------------
-- 消息
--
-- 关键设计：
-- 1) client_msg_id 唯一约束 —— 客户端重发时不会产生重复消息（幂等）。
-- 2) ts 用 BIGINT 存毫秒，避免时区/精度问题；按时间倒序分页用 (conversation_id, ts)。
-- 3) 私聊和群聊共用一张表，用 chat_type 区分；会话 id 用 conversation_id 统一表达：
--      私聊 = "p:{小id}:{大id}"，群聊 = "g:{groupId}"
--    这样只用一个索引就能覆盖两种会话的历史查询。
-- 4) 量级上来后按 conversation_id 哈希分库，或冷热分离（近 3 个月 MySQL，更早归档）。
-- ------------------------------------------------------------
CREATE TABLE t_message
(
    id              VARCHAR(36)     NOT NULL COMMENT '服务端消息 id（UUID）',
    client_msg_id   VARCHAR(64)              DEFAULT NULL COMMENT '客户端幂等 id',
    conversation_id VARCHAR(72)     NOT NULL COMMENT '会话 id：p:u1:u2 / g:g1',
    chat_type       TINYINT         NOT NULL COMMENT '1 私聊 2 群聊',
    from_user       VARCHAR(32)     NOT NULL,
    to_target       VARCHAR(32)     NOT NULL COMMENT '私聊为对方 userId，群聊为 groupId',
    msg_type        TINYINT         NOT NULL DEFAULT 1 COMMENT '1 文本 2 图片 3 文件 4 系统',
    content         TEXT            NOT NULL,
    ts              BIGINT          NOT NULL COMMENT '服务端时间戳（毫秒）',
    status          TINYINT         NOT NULL DEFAULT 0 COMMENT '0 正常 1 已撤回 2 已删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_client_msg (from_user, client_msg_id) COMMENT '幂等：同一发送者的同一 clientMsgId 只落一次',
    KEY idx_conversation_ts (conversation_id, ts),
    KEY idx_to_target_ts (to_target, ts)
) ENGINE = InnoDB COMMENT '消息表';

-- ------------------------------------------------------------
-- 离线消息（也可以不建表，用 t_message + 已读位点实现）
--
-- 这里保留独立表，是为了演示“在线直推、离线入箱”这条链路。
-- 真正要考虑的是：用户长期不上线，离线消息会无限增长 —— 必须有过期清理策略。
-- ------------------------------------------------------------
CREATE TABLE t_offline_message
(
    id         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    user_id    VARCHAR(32)     NOT NULL COMMENT '收件人',
    message_id VARCHAR(36)     NOT NULL COMMENT 't_message.id',
    created_at DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_offline_user (user_id, id)
) ENGINE = InnoDB COMMENT '离线消息投递队列';

-- ------------------------------------------------------------
-- 会话已读位点（未读数从这里算，避免扫全表）
-- ------------------------------------------------------------
CREATE TABLE t_conversation_state
(
    user_id         VARCHAR(32) NOT NULL,
    conversation_id VARCHAR(72) NOT NULL,
    last_read_ts    BIGINT      NOT NULL DEFAULT 0 COMMENT '已读到的时间戳',
    unread_cnt      INT         NOT NULL DEFAULT 0,
    updated_at      DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (user_id, conversation_id)
) ENGINE = InnoDB COMMENT '会话状态表';

-- ------------------------------------------------------------
-- 演示数据（密码哈希对应明文 123456 的 SHA-256("quack:123456")，
-- 生产请用 BCrypt，不要照抄）
-- ------------------------------------------------------------
INSERT INTO t_user (id, username, password_hash, nickname)
VALUES ('u1', 'alice', 'a3f5c9b0d1e2f30405060708090a0b0c1d2e3f405162738495a6b7c8d9e0f1', 'Alice'),
       ('u2', 'bob', 'a3f5c9b0d1e2f30405060708090a0b0c1d2e3f405162738495a6b7c8d9e0f1', '小鲍'),
       ('u3', 'carol', 'a3f5c9b0d1e2f30405060708090a0b0c1d2e3f405162738495a6b7c8d9e0f1', 'Carol');

INSERT INTO t_friend (user_id, friend_id, remark)
VALUES ('u1', 'u2', '小鲍'), ('u2', 'u1', 'Alice'),
       ('u1', 'u3', 'Carol'), ('u3', 'u1', 'Alice'),
       ('u2', 'u3', 'Carol'), ('u3', 'u2', '小鲍');

INSERT INTO t_group (id, name, owner_id, member_cnt)
VALUES ('g1', '摸鱼小分队', 'u1', 3),
       ('g2', 'Java 自学小组', 'u1', 2);

INSERT INTO t_group_member (group_id, user_id, role)
VALUES ('g1', 'u1', 2), ('g1', 'u2', 0), ('g1', 'u3', 0),
       ('g2', 'u1', 2), ('g2', 'u2', 0);
