package io.quack.server.store;

import io.quack.common.model.ChatMessage;
import io.quack.common.model.ChatType;
import io.quack.common.model.FriendRequest;
import io.quack.common.model.MsgType;
import io.quack.common.model.User;
import io.quack.common.model.UserProfile;
import io.quack.common.util.AppDirs;
import io.quack.server.service.FriendRequestService;
import io.quack.server.service.FriendService;
import io.quack.server.service.GroupService;
import io.quack.server.service.ReadService;
import io.quack.server.service.UserService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * SQLite 持久化。
 *
 * <p>元数据（账号/资料/好友/群/已读/游标）仍然“2 秒指纹比对 + 事务全量重写”，
 * 因为数据量小、最不容易漏表；**消息改成只追加**：每个会话有唯一 (conv_key, seq)，
 * 写库时只插入还没写过的消息，不再每 2 秒清空 messages 表重写一遍。
 * 这样消息表越大，单次写库成本也不会线性上升。
 */
@Component
public class PersistenceService {

    private static final Logger log = LoggerFactory.getLogger(PersistenceService.class);

    private final UserService userService;
    private final FriendService friendService;
    private final FriendRequestService friendRequestService;
    private final GroupService groupService;
    private final MessageStore messageStore;
    private final ReadService readService;
    private final OfflineCursorStore cursorStore;
    private final io.quack.server.service.UnreadService unreadService;
    private final io.quack.server.service.MomentService momentService;
    private final io.quack.server.service.GroupJoinService groupJoinService;

    private final Path file = AppDirs.dataDir().resolve("quack.db");
    private Connection connection;
    /** 元数据（不含消息与游标）的指纹 */
    private int lastMetaFingerprint = Integer.MIN_VALUE;
    /** 空间动态整体 JSON 的上一版，用来判断是否需要重写 */
    private volatile String lastMomentJson;
    private volatile boolean loaded;
    /** 已经写进 messages 表的消息 id，用来做增量插入 */
    private final Set<String> persistedMessageIds = new HashSet<>();
    /** 账号注册时间：每次重写时不能拿“当前时间”覆盖它 */
    private final Map<Long, Long> accountCreatedAt = new java.util.concurrent.ConcurrentHashMap<>();

    public PersistenceService(UserService userService, FriendService friendService,
                              FriendRequestService friendRequestService, GroupService groupService,
                              MessageStore messageStore, ReadService readService,
                              OfflineCursorStore cursorStore,
                              io.quack.server.service.UnreadService unreadService,
                              io.quack.server.service.MomentService momentService,
                              io.quack.server.service.GroupJoinService groupJoinService) {
        this.userService = userService;
        this.friendService = friendService;
        this.friendRequestService = friendRequestService;
        this.groupService = groupService;
        this.messageStore = messageStore;
        this.readService = readService;
        this.cursorStore = cursorStore;
        this.unreadService = unreadService;
        this.momentService = momentService;
        this.groupJoinService = groupJoinService;
    }

    // ------------------------------------------------------------------
    // 启动 / 关闭
    // ------------------------------------------------------------------

    @PostConstruct
    public synchronized void load() {
        try {
            Files.createDirectories(file.getParent());
            connection = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath());
            try (Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA journal_mode=WAL");
                statement.execute("PRAGMA synchronous=NORMAL");
                statement.execute("PRAGMA foreign_keys=ON");
            }
            createTables();
            migrate();
            restore(readAll());
            loadMoments();
            loaded = true;
            log.info("SQLite 已就绪：{}（{} 个账号 / {} 条消息 / {} 个离线游标 / {} 条动态）",
                    file.toAbsolutePath(), userService.count(), persistedMessageIds.size(),
                    cursorStore.snapshot().values().stream()
                            .flatMap(devices -> devices.values().stream())
                            .mapToInt(Map::size).sum(),
                    momentService.count());
        } catch (Exception error) {
            loaded = true;
            // 关键：打不开/迁移失败时不能留着一个空内存态继续跑，
            // 否则下一次 tick 会把“空快照”写回库，等于清档。
            try {
                if (connection != null) {
                    connection.close();
                }
            } catch (Exception ignored) {
                // 关闭失败也没关系，下面把 connection 置空即可
            }
            connection = null;
            log.error("打开 SQLite 失败：{}（本次运行不再落库，请修复后重启）",
                    file.toAbsolutePath(), error);
        }
    }

    @PreDestroy
    public synchronized void onShutdown() {
        try {
            saveNow();
            if (connection != null) {
                connection.close();
            }
        } catch (Exception error) {
            log.warn("关闭数据库时出错：{}", error.toString());
        }
    }

    @Scheduled(fixedDelay = 2000L, initialDelay = 2000L)
    public void tick() {
        if (!loaded || connection == null) {
            return;
        }
        try {
            saveNow();
        } catch (Exception error) {
            log.warn("保存到 SQLite 失败：{}", error.toString());
        }
    }

    // ------------------------------------------------------------------
    // 表结构
    // ------------------------------------------------------------------

    private void createTables() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS accounts (
                        qc_id INTEGER PRIMARY KEY,
                        username TEXT NOT NULL,
                        nickname TEXT,
                        avatar TEXT,
                        created_at INTEGER,
                        password_hash TEXT NOT NULL
                    )""");
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS profiles (
                        qc_id INTEGER PRIMARY KEY,
                        username TEXT,
                        nickname TEXT,
                        avatar TEXT,
                        signature TEXT,
                        gender TEXT,
                        birthday TEXT,
                        region TEXT,
                        identity TEXT,
                        likes INTEGER,
                        created_at INTEGER
                    )""");
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS friendships (
                        a TEXT NOT NULL,
                        b TEXT NOT NULL,
                        remark TEXT,
                        PRIMARY KEY (a, b)
                    )""");
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS friend_requests (
                        id TEXT PRIMARY KEY,
                        from_id TEXT,
                        from_name TEXT,
                        to_id TEXT,
                        message TEXT,
                        ts INTEGER,
                        status TEXT,
                        remark TEXT
                    )""");
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS groups (
                        id TEXT PRIMARY KEY,
                        name TEXT,
                        owner_id TEXT,
                        announcement TEXT,
                        avatar TEXT
                    )""");
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS group_members (
                        group_id TEXT NOT NULL,
                        user_id TEXT NOT NULL,
                        PRIMARY KEY (group_id, user_id)
                    )""");
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS group_aliases (
                        group_id TEXT NOT NULL,
                        user_id TEXT NOT NULL,
                        alias TEXT,
                        PRIMARY KEY (group_id, user_id)
                    )""");
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS group_announcements (
                        group_id TEXT NOT NULL,
                        id TEXT NOT NULL,
                        text TEXT,
                        author_id TEXT,
                        author_name TEXT,
                        ts INTEGER DEFAULT 0,
                        PRIMARY KEY (group_id, id)
                    )""");
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS group_admins (
                        group_id TEXT NOT NULL,
                        user_id TEXT NOT NULL,
                        PRIMARY KEY (group_id, user_id)
                    )""");
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS group_join_requests (
                        id TEXT PRIMARY KEY,
                        group_id TEXT,
                        group_name TEXT,
                        user_id TEXT,
                        user_name TEXT,
                        user_avatar TEXT,
                        status TEXT,
                        ts INTEGER DEFAULT 0,
                        handled_by TEXT,
                        handled_at INTEGER DEFAULT 0
                    )""");
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS messages (
                        id TEXT PRIMARY KEY,
                        client_msg_id TEXT,
                        from_id TEXT,
                        from_name TEXT,
                        to_id TEXT,
                        chat_type TEXT,
                        msg_type TEXT,
                        content TEXT,
                        ts INTEGER,
                        from_avatar TEXT,
                        conv_key TEXT,
                        seq INTEGER DEFAULT 0,
                        edited INTEGER DEFAULT 0
                    )""");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_messages_ts ON messages (ts)");
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS read_state (
                        chat_key TEXT NOT NULL,
                        user_id TEXT NOT NULL,
                        ts INTEGER,
                        seq INTEGER DEFAULT 0,
                        PRIMARY KEY (chat_key, user_id)
                    )""");
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS offline_cursor (
                        user_id TEXT NOT NULL,
                        device_id TEXT NOT NULL DEFAULT 'default',
                        conv_key TEXT NOT NULL,
                        seq INTEGER DEFAULT 0,
                        PRIMARY KEY (user_id, device_id, conv_key)
                    )""");
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS unread_state (
                        user_id TEXT NOT NULL,
                        conv_key TEXT NOT NULL,
                        unread INTEGER DEFAULT 0,
                        PRIMARY KEY (user_id, conv_key)
                    )""");
            // 空间动态/点赞/评论：整体 JSON 快照，量小、结构常变，比拆 3 张表好维护
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS moment_state (
                        id INTEGER PRIMARY KEY CHECK (id = 1),
                        json TEXT
                    )""");
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS meta (
                        key TEXT PRIMARY KEY,
                        value TEXT
                    )""");
        }
    }

    /**
     * 老库迁移：给 messages 补 conv_key/seq、给 read_state 补 seq，
     * 并按时间顺序把已有消息的回话序号补齐。
     */
    private void migrate() throws SQLException {
        ensureColumn("messages", "conv_key", "TEXT");
        ensureColumn("messages", "seq", "INTEGER DEFAULT 0");
        ensureColumn("messages", "edited", "INTEGER DEFAULT 0");
        ensureColumn("read_state", "seq", "INTEGER DEFAULT 0");
        migrateOfflineCursorDevice();
        backfillMessages();
        backfillReadSeq();
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE UNIQUE INDEX IF NOT EXISTS uk_messages_conv_seq"
                    + " ON messages (conv_key, seq)");
        }
    }

    /**
     * 旧版 offline_cursor 没有 device_id，主键也不对；游标本身是可重建的（最多重复补发，
     * 客户端按 id/seq 去重），所以直接重建表最省事。
     */
    private void migrateOfflineCursorDevice() throws SQLException {
        boolean hasDevice = false;
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("PRAGMA table_info(offline_cursor)")) {
            boolean exists = false;
            while (rows.next()) {
                exists = true;
                if ("device_id".equalsIgnoreCase(rows.getString("name"))) {
                    hasDevice = true;
                }
            }
            if (!exists || hasDevice) {
                return;
            }
        }
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("DROP TABLE offline_cursor");
            statement.execute("""
                    CREATE TABLE offline_cursor (
                        user_id TEXT NOT NULL,
                        device_id TEXT NOT NULL DEFAULT 'default',
                        conv_key TEXT NOT NULL,
                        seq INTEGER DEFAULT 0,
                        PRIMARY KEY (user_id, device_id, conv_key)
                    )""");
            log.info("SQLite 迁移：offline_cursor 已按设备重建（旧游标丢弃，会触发一次重复补发）");
        }
    }

    /** 老库的已读位点只有 ts：按消息表把它换算成 seq，避免升级后显示“未读” */
    private void backfillReadSeq() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    UPDATE read_state SET seq = COALESCE((
                        SELECT MAX(m.seq) FROM messages m
                        WHERE m.conv_key = read_state.chat_key AND m.ts <= read_state.ts
                    ), 0)
                    WHERE seq IS NULL OR seq = 0""");
        }
    }

    private void ensureColumn(String table, String column, String type) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (rows.next()) {
                if (column.equalsIgnoreCase(rows.getString("name"))) {
                    return;
                }
            }
        }
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("ALTER TABLE " + table + " ADD COLUMN " + column + " " + type);
            log.info("SQLite 迁移：{}.{} 已添加", table, column);
        }
    }

    private void backfillMessages() throws SQLException {
        Map<String, Long> sequence = new HashMap<>();
        List<String[]> updates = new ArrayList<>();
        String query = "SELECT id, chat_type, from_id, to_id, conv_key, seq FROM messages"
                + " ORDER BY ts ASC";
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(query)) {
            while (rows.next()) {
                String convKey = rows.getString("conv_key");
                if (convKey == null || convKey.isBlank()) {
                    convKey = convKeyOf(rows.getString("chat_type"),
                            rows.getString("from_id"), rows.getString("to_id"));
                }
                long seq = rows.getLong("seq");
                if (seq <= 0) {
                    seq = sequence.getOrDefault(convKey, 0L) + 1;
                }
                sequence.merge(convKey, seq, Math::max);
                updates.add(new String[]{rows.getString("id"), convKey, String.valueOf(seq)});
            }
        }
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE messages SET conv_key = ?, seq = ? WHERE id = ?")) {
            for (String[] update : updates) {
                ps.setString(1, update[1]);
                ps.setLong(2, Long.parseLong(update[2]));
                ps.setString(3, update[0]);
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    private static String convKeyOf(String chatType, String from, String to) {
        if ("GROUP".equalsIgnoreCase(chatType)) {
            return "GROUP:" + to;
        }
        return from.compareTo(to) <= 0
                ? "PRIVATE:" + from + ":" + to
                : "PRIVATE:" + to + ":" + from;
    }

    // ------------------------------------------------------------------
    // 读
    // ------------------------------------------------------------------

    private PersistedState readAll() throws SQLException {
        List<PersistedState.AccountState> accounts = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(
                     "SELECT qc_id, username, nickname, avatar, password_hash, created_at FROM accounts")) {
            while (rows.next()) {
                User user = new User(rows.getLong("qc_id"), rows.getString("username"),
                        rows.getString("nickname"), rows.getString("avatar"));
                accounts.add(new PersistedState.AccountState(user, rows.getString("password_hash")));
                long createdAt = rows.getLong("created_at");
                if (createdAt > 0) {
                    accountCreatedAt.put(user.qcId(), createdAt);
                }
            }
        }

        List<UserProfile> profiles = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT * FROM profiles")) {
            while (rows.next()) {
                profiles.add(new UserProfile(
                        rows.getLong("qc_id"),
                        rows.getString("username"),
                        rows.getString("nickname"),
                        rows.getString("avatar"),
                        rows.getString("signature"),
                        rows.getString("gender"),
                        rows.getString("birthday"),
                        rows.getString("region"),
                        rows.getString("identity"),
                        rows.getInt("likes"),
                        rows.getLong("created_at")));
            }
        }

        List<PersistedState.Friendship> friendships = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT a, b, remark FROM friendships")) {
            while (rows.next()) {
                friendships.add(new PersistedState.Friendship(
                        rows.getString("a"), rows.getString("b"), rows.getString("remark")));
            }
        }

        List<FriendRequest> requests = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT * FROM friend_requests")) {
            while (rows.next()) {
                requests.add(new FriendRequest(
                        rows.getString("id"),
                        rows.getString("from_id"),
                        rows.getString("from_name"),
                        rows.getString("to_id"),
                        rows.getString("message"),
                        rows.getLong("ts"),
                        rows.getString("status"),
                        rows.getString("remark")));
            }
        }

        Map<String, List<String>> members = new HashMap<>();
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT group_id, user_id FROM group_members")) {
            while (rows.next()) {
                members.computeIfAbsent(rows.getString("group_id"), key -> new ArrayList<>())
                        .add(rows.getString("user_id"));
            }
        }
        Map<String, Map<String, String>> aliases = new HashMap<>();
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT group_id, user_id, alias FROM group_aliases")) {
            while (rows.next()) {
                aliases.computeIfAbsent(rows.getString("group_id"), key -> new HashMap<>())
                        .put(rows.getString("user_id"), rows.getString("alias"));
            }
        }
        Map<String, List<io.quack.common.model.GroupAnnouncement>> announcementList = new HashMap<>();
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(
                     "SELECT group_id, id, text, author_id, author_name, ts FROM group_announcements"
                             + " ORDER BY ts DESC")) {
            while (rows.next()) {
                announcementList.computeIfAbsent(rows.getString("group_id"), key -> new ArrayList<>())
                        .add(new io.quack.common.model.GroupAnnouncement(
                                rows.getString("id"), rows.getString("text"),
                                rows.getString("author_id"), rows.getString("author_name"),
                                rows.getLong("ts")));
            }
        }
        Map<String, List<String>> admins = new HashMap<>();
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT group_id, user_id FROM group_admins")) {
            while (rows.next()) {
                admins.computeIfAbsent(rows.getString("group_id"), key -> new ArrayList<>())
                        .add(rows.getString("user_id"));
            }
        }
        List<PersistedState.GroupState> groups = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT * FROM groups")) {
            while (rows.next()) {
                String id = rows.getString("id");
                groups.add(new PersistedState.GroupState(
                        id, rows.getString("name"), rows.getString("owner_id"),
                        rows.getString("announcement"), rows.getString("avatar"),
                        members.getOrDefault(id, List.of()),
                        aliases.getOrDefault(id, Map.of()),
                        announcementList.getOrDefault(id, List.of()),
                        admins.getOrDefault(id, List.of())));
            }
        }

        List<ChatMessage> messages = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(
                     "SELECT * FROM messages ORDER BY conv_key ASC, seq ASC, ts ASC")) {
            while (rows.next()) {
                ChatMessage message = new ChatMessage(
                        rows.getString("id"),
                        rows.getString("client_msg_id"),
                        rows.getString("from_id"),
                        rows.getString("from_name"),
                        rows.getString("to_id"),
                        parseEnum(ChatType.class, rows.getString("chat_type"), ChatType.PRIVATE),
                        parseEnum(MsgType.class, rows.getString("msg_type"), MsgType.TEXT),
                        rows.getString("content"),
                        rows.getLong("ts"),
                        rows.getString("from_avatar"),
                        rows.getLong("seq"),
                        rows.getInt("edited") != 0);
                messages.add(message);
                persistedMessageIds.add(message.id());
            }
        }

        Map<String, Map<String, PersistedState.ReadPos>> readAt = new LinkedHashMap<>();
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT chat_key, user_id, ts, seq FROM read_state")) {
            while (rows.next()) {
                readAt.computeIfAbsent(rows.getString("chat_key"), key -> new LinkedHashMap<>())
                        .put(rows.getString("user_id"),
                                new PersistedState.ReadPos(rows.getLong("seq"), rows.getLong("ts")));
            }
        }

        Map<String, Map<String, Map<String, Long>>> offlineCursor = new LinkedHashMap<>();
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(
                     "SELECT user_id, device_id, conv_key, seq FROM offline_cursor")) {
            while (rows.next()) {
                offlineCursor
                        .computeIfAbsent(rows.getString("user_id"), key -> new LinkedHashMap<>())
                        .computeIfAbsent(rows.getString("device_id"), key -> new LinkedHashMap<>())
                        .put(rows.getString("conv_key"), rows.getLong("seq"));
            }
        }

        Map<String, Map<String, Integer>> unread = new LinkedHashMap<>();
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT user_id, conv_key, unread FROM unread_state")) {
            while (rows.next()) {
                unread.computeIfAbsent(rows.getString("user_id"), key -> new LinkedHashMap<>())
                        .put(rows.getString("conv_key"), rows.getInt("unread"));
            }
        }

        long qcSequence = 10000L;
        long groupSequence = 10000L;
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT key, value FROM meta")) {
            while (rows.next()) {
                if ("qc_sequence".equals(rows.getString("key"))) {
                    qcSequence = Long.parseLong(rows.getString("value"));
                } else if ("group_sequence".equals(rows.getString("key"))) {
                    groupSequence = Long.parseLong(rows.getString("value"));
                }
            }
        }

        List<io.quack.common.model.GroupJoinRequest> joinRequests = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT * FROM group_join_requests")) {
            while (rows.next()) {
                joinRequests.add(new io.quack.common.model.GroupJoinRequest(
                        rows.getString("id"), rows.getString("group_id"),
                        rows.getString("group_name"), rows.getString("user_id"),
                        rows.getString("user_name"), rows.getString("user_avatar"),
                        rows.getString("status"), rows.getLong("ts"),
                        rows.getString("handled_by"), rows.getLong("handled_at")));
            }
        }

        return new PersistedState(PersistedState.CURRENT_VERSION, accounts, profiles, qcSequence,
                friendships, requests, groups, groupSequence, messages, readAt, offlineCursor, unread,
                joinRequests);
    }

    private static <E extends Enum<E>> E parseEnum(Class<E> type, String raw, E fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return Enum.valueOf(type, raw);
        } catch (IllegalArgumentException error) {
            return fallback;
        }
    }

    private void restore(PersistedState state) {
        userService.restoreAccounts(state.accounts());
        userService.restoreProfiles(state.profiles());
        userService.restoreQcSequence(state.qcSequence());
        friendService.restoreFriendships(state.friendships());
        friendRequestService.restoreRequests(state.requests());
        groupService.restoreGroups(state.groups());
        groupService.restoreGroupSequence(state.groupSequence());
        messageStore.restore(state.messages());
        readService.restore(state.readAt());
        cursorStore.restore(state.offlineCursor());
        unreadService.restore(state.unread());
        groupJoinService.restore(state.joinRequests());
    }

    /** 空间动态：整体 JSON 快照（没有就保持空） */
    private void loadMoments() {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT json FROM moment_state WHERE id = 1")) {
            if (rows.next()) {
                String json = rows.getString(1);
                momentService.importState(json);
                lastMomentJson = json;
            }
        } catch (SQLException error) {
            log.warn("读取空间动态失败：{}", error.toString());
        }
    }

    private void writeMoments(String json) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT OR REPLACE INTO moment_state (id, json) VALUES (1, ?)")) {
            ps.setString(1, json);
            ps.executeUpdate();
        }
    }

    // ------------------------------------------------------------------
    // 写
    // ------------------------------------------------------------------

    /** 立即落盘：元数据有变化就重写；消息只插入新的；游标有变化就重写 */
    public synchronized void saveNow() throws SQLException {
        if (connection == null) {
            // 启动时打开/迁移失败：宁可不写，也不能用空快照覆盖已有数据
            return;
        }
        PersistedState state = snapshot();
        int fingerprint = fingerprint(state);
        boolean metaChanged = fingerprint != lastMetaFingerprint;
        List<ChatMessage> newMessages = new ArrayList<>();
        Set<String> newIds = new HashSet<>();
        for (ChatMessage message : state.messages()) {
            if (!persistedMessageIds.contains(message.id())) {
                newMessages.add(message);
                newIds.add(message.id());
            }
        }
        // 被撤回修改过的消息：同一行要覆盖写
        List<ChatMessage> dirtyMessages = messageStore.dirtyMessages();
        List<ChatMessage> toWriteMessages = new ArrayList<>(newMessages);
        for (ChatMessage message : dirtyMessages) {
            if (!newIds.contains(message.id())) {
                toWriteMessages.add(message);
            }
        }
        boolean cursorChanged = cursorStore.isDirty();
        boolean unreadChanged = unreadService.isDirty();
        String momentJson = momentService.exportState();
        boolean momentsChanged = !momentJson.equals(lastMomentJson);
        if (!metaChanged && toWriteMessages.isEmpty() && !cursorChanged && !unreadChanged
                && !momentsChanged) {
            return;
        }

        connection.setAutoCommit(false);
        try {
            if (metaChanged) {
                clearMetadataTables();
                insertAccounts(state.accounts());
                insertProfiles(state.profiles());
                insertFriendships(state.friendships());
                insertRequests(state.requests());
                insertGroups(state.groups());
                insertJoinRequests(state.joinRequests());
                insertReadAt(state.readAt());
                putMeta("qc_sequence", String.valueOf(state.qcSequence()));
                putMeta("group_sequence", String.valueOf(state.groupSequence()));
            }
            if (!toWriteMessages.isEmpty()) {
                insertMessages(toWriteMessages);
            }
            if (cursorChanged) {
                clearOfflineCursor();
                insertOfflineCursor(state.offlineCursor());
            }
            if (unreadChanged) {
                clearUnread();
                insertUnread(state.unread());
            }
            if (momentsChanged) {
                writeMoments(momentJson);
            }
            connection.commit();
        } catch (SQLException error) {
            connection.rollback();
            throw error;
        } finally {
            connection.setAutoCommit(true);
        }

        if (metaChanged) {
            lastMetaFingerprint = fingerprint;
        }
        for (ChatMessage message : newMessages) {
            persistedMessageIds.add(message.id());
        }
        if (!dirtyMessages.isEmpty()) {
            messageStore.clearDirty(dirtyMessages.stream().map(ChatMessage::id).toList());
        }
        if (cursorChanged) {
            // 只在提交成功后才清掉 dirty，失败时下一轮会重试
            cursorStore.consumeDirty();
        }
        if (unreadChanged) {
            unreadService.consumeDirty();
        }
        if (momentsChanged) {
            lastMomentJson = momentJson;
        }
    }

    /**
     * 元数据指纹。
     *
     * <p>不能直接 hash 原集合：快照来自 ConcurrentHashMap / Set，迭代顺序每次都可能不同，
     * 顺序一抖就会被判成“数据变了”，退化成每 2 秒无条件全表重写；
     * 这里先排成稳定的规范形式再 hash。消息与游标单独增量处理，不参与该指纹。
     */
    private static int fingerprint(PersistedState state) {
        List<String> canon = new ArrayList<>();
        for (PersistedState.AccountState account : state.accounts()) {
            canon.add("a|" + account.user().qcId() + "|" + account.user().username()
                    + "|" + account.user().nickname() + "|" + account.user().avatar()
                    + "|" + account.passwordHash());
        }
        List<UserProfile> profiles = new ArrayList<>(state.profiles());
        profiles.sort(java.util.Comparator.comparingLong(UserProfile::qcId));
        for (UserProfile profile : profiles) {
            canon.add("p|" + profile);
        }
        List<PersistedState.Friendship> friendships = new ArrayList<>(state.friendships());
        friendships.sort(java.util.Comparator.comparing(PersistedState.Friendship::a)
                .thenComparing(PersistedState.Friendship::b));
        for (PersistedState.Friendship friendship : friendships) {
            canon.add("f|" + friendship);
        }
        List<FriendRequest> requests = new ArrayList<>(state.requests());
        requests.sort(java.util.Comparator.comparing(FriendRequest::id));
        for (FriendRequest request : requests) {
            canon.add("r|" + request);
        }
        List<PersistedState.GroupState> groups = new ArrayList<>(state.groups());
        groups.sort(java.util.Comparator.comparing(PersistedState.GroupState::id));
        for (PersistedState.GroupState group : groups) {
            List<String> members = new ArrayList<>(
                    group.members() == null ? List.of() : group.members());
            java.util.Collections.sort(members);
            canon.add("g|" + group.id() + "|" + group.name() + "|" + group.ownerId()
                    + "|" + group.announcement() + "|" + group.avatar() + "|" + members
                    + "|" + new java.util.TreeMap<>(group.aliases() == null ? Map.of() : group.aliases())
                    + "|" + (group.announcementList() == null ? List.of() : group.announcementList())
                    + "|" + (group.admins() == null ? List.of() : group.admins()));
        }
        List<String> readKeys = new ArrayList<>(state.readAt().keySet());
        java.util.Collections.sort(readKeys);
        for (String key : readKeys) {
            java.util.TreeMap<String, PersistedState.ReadPos> entries =
                    new java.util.TreeMap<>(state.readAt().get(key));
            canon.add("x|" + key + "|" + entries);
        }
        canon.add("q|" + state.qcSequence());
        canon.add("s|" + state.groupSequence());
        if (state.joinRequests() != null) {
            List<io.quack.common.model.GroupJoinRequest> joins =
                    new ArrayList<>(state.joinRequests());
            joins.sort(java.util.Comparator.comparing(io.quack.common.model.GroupJoinRequest::id));
            joins.forEach(request -> canon.add("j|" + request));
        }
        return canon.hashCode();
    }

    public PersistedState snapshot() {
        return new PersistedState(
                PersistedState.CURRENT_VERSION,
                userService.snapshotAccounts(),
                userService.snapshotProfiles(),
                userService.qcSequenceNow(),
                friendService.snapshotFriendships(),
                friendRequestService.snapshotRequests(),
                groupService.snapshotGroups(),
                groupService.groupSequenceNow(),
                messageStore.snapshot(),
                readService.snapshot(),
                cursorStore.snapshot(),
                unreadService.snapshot(),
                groupJoinService.snapshot());
    }

    /** 只清元数据表；messages 和 offline_cursor 走增量，不在这里删 */
    private void clearMetadataTables() throws SQLException {
        for (String table : List.of("accounts", "profiles", "friendships", "friend_requests",
                "groups", "group_members", "read_state")) {
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("DELETE FROM " + table);
            }
        }
    }

    private void clearOfflineCursor() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM offline_cursor");
        }
    }

    private void clearUnread() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM unread_state");
        }
    }

    private void insertUnread(Map<String, Map<String, Integer>> unread) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT OR REPLACE INTO unread_state (user_id, conv_key, unread) VALUES (?, ?, ?)")) {
            for (Map.Entry<String, Map<String, Integer>> user : unread.entrySet()) {
                for (Map.Entry<String, Integer> inner : user.getValue().entrySet()) {
                    ps.setString(1, user.getKey());
                    ps.setString(2, inner.getKey());
                    ps.setInt(3, inner.getValue());
                    ps.addBatch();
                }
            }
            ps.executeBatch();
        }
    }

    private void insertAccounts(List<PersistedState.AccountState> accounts) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO accounts (qc_id, username, nickname, avatar, created_at, password_hash)"
                        + " VALUES (?, ?, ?, ?, ?, ?)")) {
            for (PersistedState.AccountState state : accounts) {
                ps.setLong(1, state.user().qcId());
                ps.setString(2, state.user().username());
                ps.setString(3, state.user().nickname());
                ps.setString(4, state.user().avatar());
                // 全量重写时保留首次落库的注册时间，不要每次同步都刷成 now
                long createdAt = accountCreatedAt.computeIfAbsent(state.user().qcId(),
                        id -> System.currentTimeMillis());
                ps.setLong(5, createdAt);
                ps.setString(6, state.passwordHash());
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    private void insertProfiles(List<UserProfile> profiles) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO profiles (qc_id, username, nickname, avatar, signature, gender,"
                        + " birthday, region, identity, likes, created_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            for (UserProfile profile : profiles) {
                ps.setLong(1, profile.qcId());
                ps.setString(2, profile.username());
                ps.setString(3, profile.nickname());
                ps.setString(4, profile.avatar());
                ps.setString(5, profile.signature());
                ps.setString(6, profile.gender());
                ps.setString(7, profile.birthday());
                ps.setString(8, profile.region());
                ps.setString(9, profile.identity());
                ps.setInt(10, profile.likes());
                ps.setLong(11, profile.createdAt());
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    private void insertFriendships(List<PersistedState.Friendship> list) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT OR REPLACE INTO friendships (a, b, remark) VALUES (?, ?, ?)")) {
            for (PersistedState.Friendship friendship : list) {
                ps.setString(1, friendship.a());
                ps.setString(2, friendship.b());
                ps.setString(3, friendship.remark());
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    private void insertRequests(List<FriendRequest> list) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT OR REPLACE INTO friend_requests (id, from_id, from_name, to_id, message,"
                        + " ts, status, remark) VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
            for (FriendRequest request : list) {
                ps.setString(1, request.id());
                ps.setString(2, request.fromId());
                ps.setString(3, request.fromName());
                ps.setString(4, request.toId());
                ps.setString(5, request.message());
                ps.setLong(6, request.ts());
                ps.setString(7, request.status());
                ps.setString(8, request.remark());
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    private void insertGroups(List<PersistedState.GroupState> list) throws SQLException {
        try (PreparedStatement group = connection.prepareStatement(
                "INSERT OR REPLACE INTO groups (id, name, owner_id, announcement, avatar)"
                        + " VALUES (?, ?, ?, ?, ?)");
             PreparedStatement member = connection.prepareStatement(
                     "INSERT OR REPLACE INTO group_members (group_id, user_id) VALUES (?, ?)");
             PreparedStatement alias = connection.prepareStatement(
                     "INSERT OR REPLACE INTO group_aliases (group_id, user_id, alias) VALUES (?, ?, ?)");
             PreparedStatement announcement = connection.prepareStatement(
                     "INSERT OR REPLACE INTO group_announcements"
                             + " (group_id, id, text, author_id, author_name, ts) VALUES (?, ?, ?, ?, ?, ?)");
             PreparedStatement admin = connection.prepareStatement(
                     "INSERT OR REPLACE INTO group_admins (group_id, user_id) VALUES (?, ?)")) {
            // 群昵称/公告/管理员是完整快照，先清空再写入
            try (Statement clear = connection.createStatement()) {
                clear.executeUpdate("DELETE FROM group_aliases");
                clear.executeUpdate("DELETE FROM group_announcements");
                clear.executeUpdate("DELETE FROM group_admins");
            }
            for (PersistedState.GroupState state : list) {
                group.setString(1, state.id());
                group.setString(2, state.name());
                group.setString(3, state.ownerId());
                group.setString(4, state.announcement());
                group.setString(5, state.avatar());
                group.addBatch();
                if (state.members() != null) {
                    for (String userId : state.members()) {
                        member.setString(1, state.id());
                        member.setString(2, userId);
                        member.addBatch();
                    }
                }
                if (state.aliases() != null) {
                    for (Map.Entry<String, String> entry : state.aliases().entrySet()) {
                        if (entry.getValue() == null || entry.getValue().isBlank()) {
                            continue;
                        }
                        alias.setString(1, state.id());
                        alias.setString(2, entry.getKey());
                        alias.setString(3, entry.getValue());
                        alias.addBatch();
                    }
                }
                if (state.announcementList() != null) {
                    for (io.quack.common.model.GroupAnnouncement item : state.announcementList()) {
                        if (item == null || item.id() == null || item.text() == null) {
                            continue;
                        }
                        announcement.setString(1, state.id());
                        announcement.setString(2, item.id());
                        announcement.setString(3, item.text());
                        announcement.setString(4, item.authorId());
                        announcement.setString(5, item.authorName());
                        announcement.setLong(6, item.ts());
                        announcement.addBatch();
                    }
                }
                if (state.admins() != null) {
                    for (String userId : state.admins()) {
                        if (userId == null || userId.isBlank()) {
                            continue;
                        }
                        admin.setString(1, state.id());
                        admin.setString(2, userId);
                        admin.addBatch();
                    }
                }
            }
            group.executeBatch();
            member.executeBatch();
            alias.executeBatch();
            announcement.executeBatch();
            admin.executeBatch();
        }
    }

    private void insertJoinRequests(List<io.quack.common.model.GroupJoinRequest> list) throws SQLException {
        try (Statement clear = connection.createStatement()) {
            clear.executeUpdate("DELETE FROM group_join_requests");
        }
        if (list == null || list.isEmpty()) {
            return;
        }
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT OR REPLACE INTO group_join_requests"
                        + " (id, group_id, group_name, user_id, user_name, user_avatar,"
                        + " status, ts, handled_by, handled_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            for (io.quack.common.model.GroupJoinRequest request : list) {
                ps.setString(1, request.id());
                ps.setString(2, request.groupId());
                ps.setString(3, request.groupName());
                ps.setString(4, request.userId());
                ps.setString(5, request.userName());
                ps.setString(6, request.userAvatar());
                ps.setString(7, request.status());
                ps.setLong(8, request.ts());
                ps.setString(9, request.handledBy());
                ps.setLong(10, request.handledAt());
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    /** 写入新消息与“被撤回修改过”的消息；INSERT OR REPLACE 保证同一行覆盖 */
    private void insertMessages(List<ChatMessage> messages) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT OR REPLACE INTO messages (id, client_msg_id, from_id, from_name, to_id,"
                        + " chat_type, msg_type, content, ts, from_avatar, conv_key, seq, edited)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            for (ChatMessage message : messages) {
                ps.setString(1, message.id());
                ps.setString(2, message.clientMsgId());
                ps.setString(3, message.from());
                ps.setString(4, message.fromName());
                ps.setString(5, message.to());
                ps.setString(6, message.chatType() == null ? null : message.chatType().name());
                ps.setString(7, message.msgType() == null ? null : message.msgType().name());
                ps.setString(8, message.content());
                ps.setLong(9, message.ts());
                ps.setString(10, message.fromAvatar());
                ps.setString(11, message.conversationKey());
                ps.setLong(12, message.seq());
                ps.setInt(13, message.edited() ? 1 : 0);
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    private void insertReadAt(Map<String, Map<String, PersistedState.ReadPos>> readAt)
            throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT OR REPLACE INTO read_state (chat_key, user_id, ts, seq) VALUES (?, ?, ?, ?)")) {
            for (Map.Entry<String, Map<String, PersistedState.ReadPos>> entry : readAt.entrySet()) {
                for (Map.Entry<String, PersistedState.ReadPos> inner : entry.getValue().entrySet()) {
                    ps.setString(1, entry.getKey());
                    ps.setString(2, inner.getKey());
                    ps.setLong(3, inner.getValue().ts());
                    ps.setLong(4, inner.getValue().seq());
                    ps.addBatch();
                }
            }
            ps.executeBatch();
        }
    }

    private void insertOfflineCursor(Map<String, Map<String, Map<String, Long>>> cursors)
            throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT OR REPLACE INTO offline_cursor (user_id, device_id, conv_key, seq)"
                        + " VALUES (?, ?, ?, ?)")) {
            for (Map.Entry<String, Map<String, Map<String, Long>>> user : cursors.entrySet()) {
                for (Map.Entry<String, Map<String, Long>> device : user.getValue().entrySet()) {
                    for (Map.Entry<String, Long> inner : device.getValue().entrySet()) {
                        ps.setString(1, user.getKey());
                        ps.setString(2, device.getKey());
                        ps.setString(3, inner.getKey());
                        ps.setLong(4, inner.getValue());
                        ps.addBatch();
                    }
                }
            }
            ps.executeBatch();
        }
    }

    private void putMeta(String key, String value) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT OR REPLACE INTO meta (key, value) VALUES (?, ?)")) {
            ps.setString(1, key);
            ps.setString(2, value);
            ps.executeUpdate();
        }
    }

    /** 数据文件位置（部署/排查用） */
    public Path file() {
        return file;
    }

    /** 给测试用：各表规模 */
    public Map<String, Integer> sizes() {
        Map<String, Integer> sizes = new LinkedHashMap<>();
        for (String table : List.of("accounts", "profiles", "friendships", "friend_requests",
                "groups", "group_members", "messages", "read_state", "offline_cursor")) {
            try (Statement statement = connection.createStatement();
                 ResultSet rows = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
                sizes.put(table, rows.next() ? rows.getInt(1) : 0);
            } catch (SQLException error) {
                sizes.put(table, -1);
            }
        }
        return sizes;
    }
}
