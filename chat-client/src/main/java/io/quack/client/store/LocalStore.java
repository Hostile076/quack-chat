package io.quack.client.store;

import io.quack.common.model.ChatMessage;
import io.quack.common.model.ChatType;
import io.quack.common.model.MsgType;
import io.quack.common.util.AppDirs;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 客户端本地库（每个账号一个 SQLite 文件）：
 * 聊天记录、每会话最后 seq、已读位点、输入草稿。
 *
 * <p>目标：打开会话时先用本地数据渲染，再向服务端 sync 缺口；重启客户端后历史还在。
 * 写操作走单线程队列，避免 UI 线程被磁盘 IO 卡住。
 */
public class LocalStore implements AutoCloseable {

    private final long qcId;
    private final Connection connection;
    private final ExecutorService writer = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "local-store");
        thread.setDaemon(true);
        return thread;
    });

    /** 离线登录时用来重建会话列表 */
    public record OfflineConversation(String convKey, ChatType chatType, String peerId,
                                      String title, String avatar, long lastSeq, int unread) {
    }

    public static LocalStore open(long qcId) {
        try {
            return new LocalStore(qcId);
        } catch (Exception error) {
            // 本地库只是加速与离线可用，打不开也不能影响聊天
            java.util.logging.Logger.getLogger(LocalStore.class.getName())
                    .warning("打开本地库失败，本次不缓存聊天记录: " + error);
            return null;
        }
    }

    private LocalStore(long qcId) throws Exception {
        this.qcId = qcId;
        Class.forName("org.sqlite.JDBC");
        Path dir = AppDirs.subDir("local");
        Files.createDirectories(dir);
        this.connection = DriverManager.getConnection(
                "jdbc:sqlite:" + dir.resolve(qcId + ".db").toAbsolutePath());
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA journal_mode=WAL");
            statement.execute("PRAGMA synchronous=NORMAL");
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS messages (
                        id TEXT PRIMARY KEY,
                        conv_key TEXT NOT NULL,
                        seq INTEGER DEFAULT 0,
                        client_msg_id TEXT,
                        from_id TEXT,
                        from_name TEXT,
                        to_id TEXT,
                        chat_type TEXT,
                        msg_type TEXT,
                        content TEXT,
                        ts INTEGER,
                        from_avatar TEXT,
                        edited INTEGER DEFAULT 0
                    )""");
            // 老版本本地库没有 edited 列：补一列（失败说明已经有了）
            try {
                statement.execute("ALTER TABLE messages ADD COLUMN edited INTEGER DEFAULT 0");
            } catch (SQLException ignored) {
                // 列已存在
            }
            statement.execute("CREATE INDEX IF NOT EXISTS idx_local_conv ON messages (conv_key, seq)");
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS conv_state (
                        conv_key TEXT PRIMARY KEY,
                        last_seq INTEGER DEFAULT 0
                    )""");
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS conversations (
                        conv_key TEXT PRIMARY KEY,
                        chat_type TEXT,
                        peer_id TEXT,
                        title TEXT,
                        avatar TEXT,
                        last_seq INTEGER DEFAULT 0,
                        unread INTEGER DEFAULT 0
                    )""");
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS drafts (
                        conv_key TEXT PRIMARY KEY,
                        text TEXT
                    )""");
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS read_state (
                        conv_key TEXT NOT NULL,
                        reader_id TEXT NOT NULL,
                        seq INTEGER DEFAULT 0,
                        PRIMARY KEY (conv_key, reader_id)
                    )""");
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS group_seen (
                        group_id TEXT PRIMARY KEY,
                        announcement_hash TEXT
                    )""");
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS group_announcement_seen (
                        group_id TEXT NOT NULL,
                        hash TEXT NOT NULL,
                        PRIMARY KEY (group_id, hash)
                    )""");
        }
    }

    public long qcId() {
        return qcId;
    }

    // ------------------------------------------------------------------
    // 读
    // ------------------------------------------------------------------

    /** 群公告已读标记：返回上次“收到”的公告哈希 */
    public synchronized String announcementSeen(String groupId) {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT announcement_hash FROM group_seen WHERE group_id = ?")) {
            ps.setString(1, groupId);
            try (ResultSet rows = ps.executeQuery()) {
                return rows.next() ? rows.getString(1) : "";
            }
        } catch (SQLException error) {
            return "";
        }
    }

    public void markAnnouncementSeen(String groupId, String hash) {
        writer.execute(() -> {
            synchronized (this) {
                try (PreparedStatement ps = connection.prepareStatement(
                        "INSERT OR REPLACE INTO group_seen (group_id, announcement_hash) VALUES (?, ?)")) {
                    ps.setString(1, groupId);
                    ps.setString(2, hash);
                    ps.executeUpdate();
                } catch (SQLException ignored) {
                    // 本地标记失败不影响公告显示
                }
            }
        });
    }

    /** 单条公告是否已读（多条公告各存一个哈希） */
    public synchronized boolean announcementHashSeen(String groupId, String hash) {
        if (groupId == null || hash == null) {
            return false;
        }
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT 1 FROM group_announcement_seen WHERE group_id = ? AND hash = ?")) {
            ps.setString(1, groupId);
            ps.setString(2, hash);
            try (ResultSet rows = ps.executeQuery()) {
                return rows.next();
            }
        } catch (SQLException error) {
            return false;
        }
    }

    public void markAnnouncementHashSeen(String groupId, String hash) {
        if (groupId == null || hash == null) {
            return;
        }
        writer.execute(() -> {
            synchronized (this) {
                try (PreparedStatement ps = connection.prepareStatement(
                        "INSERT OR IGNORE INTO group_announcement_seen (group_id, hash) VALUES (?, ?)")) {
                    ps.setString(1, groupId);
                    ps.setString(2, hash);
                    ps.executeUpdate();
                } catch (SQLException ignored) {
                    // 本地标记失败不影响公告显示
                }
            }
        });
    }

    /** 公告内容哈希：内容变了就重新弹一次 */
    public static String hashAnnouncement(String text) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest((text == null ? "" : text).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(bytes);
        } catch (Exception error) {
            return Integer.toHexString((text == null ? "" : text).hashCode());
        }
    }

    /** 某个会话最近 N 条消息（按 seq 升序返回） */
    public synchronized List<ChatMessage> loadMessages(String conversationKey, int limit) {
        List<ChatMessage> result = new ArrayList<>();
        String sql = "SELECT * FROM messages WHERE conv_key = ?"
                + " ORDER BY seq DESC, ts DESC LIMIT ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, conversationKey);
            ps.setInt(2, Math.max(1, Math.min(limit, 3000)));
            try (ResultSet rows = ps.executeQuery()) {
                while (rows.next()) {
                    result.add(map(rows));
                }
            }
        } catch (SQLException error) {
            return List.of();
        }
        java.util.Collections.reverse(result);
        return result;
    }

    public synchronized long lastSeq(String conversationKey) {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT last_seq FROM conv_state WHERE conv_key = ?")) {
            ps.setString(1, conversationKey);
            try (ResultSet rows = ps.executeQuery()) {
                return rows.next() ? rows.getLong(1) : 0;
            }
        } catch (SQLException error) {
            return 0;
        }
    }

    public synchronized String draft(String conversationKey) {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT text FROM drafts WHERE conv_key = ?")) {
            ps.setString(1, conversationKey);
            try (ResultSet rows = ps.executeQuery()) {
                return rows.next() ? rows.getString(1) : "";
            }
        } catch (SQLException error) {
            return "";
        }
    }

    public synchronized Map<String, Long> readSeqs(String conversationKey) {
        Map<String, Long> result = new LinkedHashMap<>();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT reader_id, seq FROM read_state WHERE conv_key = ?")) {
            ps.setString(1, conversationKey);
            try (ResultSet rows = ps.executeQuery()) {
                while (rows.next()) {
                    result.put(rows.getString(1), rows.getLong(2));
                }
            }
        } catch (SQLException error) {
            return Map.of();
        }
        return result;
    }

    /** 离线登录：读取本地缓存的会话列表 */
    public synchronized List<OfflineConversation> loadConversations() {
        List<OfflineConversation> result = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT * FROM conversations")) {
            while (rows.next()) {
                ChatType type = "GROUP".equalsIgnoreCase(rows.getString("chat_type"))
                        ? ChatType.GROUP : ChatType.PRIVATE;
                result.add(new OfflineConversation(
                        rows.getString("conv_key"), type, rows.getString("peer_id"),
                        rows.getString("title"), rows.getString("avatar"),
                        rows.getLong("last_seq"), rows.getInt("unread")));
            }
        } catch (SQLException error) {
            return List.of();
        }
        result.sort((a, b) -> a.convKey().compareTo(b.convKey()));
        return result;
    }

    /** 在线登录/会话变化后刷新本地会话快照 */
    public void saveConversations(List<OfflineConversation> conversations) {
        if (conversations == null) {
            return;
        }
        List<OfflineConversation> copy = new ArrayList<>(conversations);
        writer.execute(() -> {
            synchronized (this) {
                try (PreparedStatement ps = connection.prepareStatement(
                        "INSERT OR REPLACE INTO conversations"
                                + " (conv_key, chat_type, peer_id, title, avatar, last_seq, unread)"
                                + " VALUES (?, ?, ?, ?, ?, ?, ?)")) {
                    for (OfflineConversation conversation : copy) {
                        ps.setString(1, conversation.convKey());
                        ps.setString(2, conversation.chatType() == null ? null : conversation.chatType().name());
                        ps.setString(3, conversation.peerId());
                        ps.setString(4, conversation.title());
                        ps.setString(5, conversation.avatar());
                        ps.setLong(6, conversation.lastSeq());
                        ps.setInt(7, conversation.unread());
                        ps.addBatch();
                    }
                    ps.executeBatch();
                } catch (SQLException ignored) {
                    // 本地缓存失败不影响主流程
                }
            }
        });
    }

    // ------------------------------------------------------------------
    // 写（异步，单线程）
    // ------------------------------------------------------------------

    public void saveMessages(String conversationKey, List<ChatMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return;
        }
        List<ChatMessage> copy = new ArrayList<>(messages);
        writer.execute(() -> saveMessagesNow(conversationKey, copy));
    }

    public void saveMessage(String conversationKey, ChatMessage message) {
        if (message == null) {
            return;
        }
        writer.execute(() -> saveMessagesNow(conversationKey, List.of(message)));
    }

    public void saveLastSeq(String conversationKey, long seq) {
        writer.execute(() -> {
            synchronized (this) {
                try (PreparedStatement ps = connection.prepareStatement(
                        "INSERT INTO conv_state (conv_key, last_seq) VALUES (?, ?)"
                                + " ON CONFLICT(conv_key) DO UPDATE SET last_seq = MAX(last_seq, ?)")) {
                    ps.setString(1, conversationKey);
                    ps.setLong(2, seq);
                    ps.setLong(3, seq);
                    ps.executeUpdate();
                } catch (SQLException ignored) {
                    // 本地缓存失败不影响主流程
                }
                try (PreparedStatement ps = connection.prepareStatement(
                        "UPDATE conversations SET last_seq = MAX(last_seq, ?) WHERE conv_key = ?")) {
                    ps.setLong(1, seq);
                    ps.setString(2, conversationKey);
                    ps.executeUpdate();
                } catch (SQLException ignored) {
                    // 会话行可能还不存在，忽略
                }
            }
        });
    }

    public void saveDraft(String conversationKey, String text) {
        String value = text == null ? "" : text;
        writer.execute(() -> {
            synchronized (this) {
                try (PreparedStatement ps = connection.prepareStatement(
                        "INSERT OR REPLACE INTO drafts (conv_key, text) VALUES (?, ?)")) {
                    ps.setString(1, conversationKey);
                    ps.setString(2, value);
                    ps.executeUpdate();
                } catch (SQLException ignored) {
                    // 忽略
                }
            }
        });
    }

    public void saveReadSeq(String conversationKey, String readerId, long seq) {
        writer.execute(() -> {
            synchronized (this) {
                try (PreparedStatement ps = connection.prepareStatement(
                        "INSERT INTO read_state (conv_key, reader_id, seq) VALUES (?, ?, ?)"
                                + " ON CONFLICT(conv_key, reader_id) DO UPDATE SET seq = MAX(seq, ?)")) {
                    ps.setString(1, conversationKey);
                    ps.setString(2, readerId);
                    ps.setLong(3, seq);
                    ps.setLong(4, seq);
                    ps.executeUpdate();
                } catch (SQLException ignored) {
                    // 忽略
                }
            }
        });
    }

    private void saveMessagesNow(String conversationKey, List<ChatMessage> messages) {
        synchronized (this) {
            try (PreparedStatement ps = connection.prepareStatement(
                    "INSERT OR REPLACE INTO messages (id, conv_key, seq, client_msg_id, from_id,"
                            + " from_name, to_id, chat_type, msg_type, content, ts, from_avatar, edited)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                for (ChatMessage message : messages) {
                    ps.setString(1, message.id());
                    ps.setString(2, conversationKey);
                    ps.setLong(3, message.seq());
                    ps.setString(4, message.clientMsgId());
                    ps.setString(5, message.from());
                    ps.setString(6, message.fromName());
                    ps.setString(7, message.to());
                    ps.setString(8, message.chatType() == null ? null : message.chatType().name());
                    ps.setString(9, message.msgType() == null ? null : message.msgType().name());
                    ps.setString(10, message.content());
                    ps.setLong(11, message.ts());
                    ps.setString(12, message.fromAvatar());
                    ps.setInt(13, message.edited() ? 1 : 0);
                    ps.addBatch();
                }
                ps.executeBatch();
            } catch (SQLException ignored) {
                // 本地缓存失败不影响主流程
            }
        }
    }

    private static ChatMessage map(ResultSet rows) throws SQLException {
        return new ChatMessage(
                rows.getString("id"),
                rows.getString("client_msg_id"),
                rows.getString("from_id"),
                rows.getString("from_name"),
                rows.getString("to_id"),
                "GROUP".equalsIgnoreCase(rows.getString("chat_type")) ? ChatType.GROUP : ChatType.PRIVATE,
                "GIFT".equalsIgnoreCase(rows.getString("msg_type")) ? MsgType.GIFT
                        : "IMAGE".equalsIgnoreCase(rows.getString("msg_type")) ? MsgType.IMAGE
                        : "VIDEO".equalsIgnoreCase(rows.getString("msg_type")) ? MsgType.VIDEO
                        : "FILE".equalsIgnoreCase(rows.getString("msg_type")) ? MsgType.FILE
                        : "SYSTEM".equalsIgnoreCase(rows.getString("msg_type")) ? MsgType.SYSTEM
                        : MsgType.TEXT,
                rows.getString("content"),
                rows.getLong("ts"),
                rows.getString("from_avatar"),
                rows.getLong("seq"),
                rows.getInt("edited") != 0);
    }

    /** 退出/被移出群：把本地这个会话的数据一起清掉，避免离线登录又冒出来 */
    public void deleteConversation(String conversationKey) {
        if (conversationKey == null) {
            return;
        }
        String groupId = conversationKey.startsWith("GROUP:")
                ? conversationKey.substring("GROUP:".length()) : null;
        writer.execute(() -> {
            synchronized (this) {
                try (PreparedStatement ps = connection.prepareStatement(
                        "DELETE FROM messages WHERE conv_key = ?")) {
                    ps.setString(1, conversationKey);
                    ps.executeUpdate();
                } catch (SQLException ignored) {
                    // 本地清理失败不影响主流程
                }
                for (String sql : new String[]{
                        "DELETE FROM conv_state WHERE conv_key = ?",
                        "DELETE FROM drafts WHERE conv_key = ?",
                        "DELETE FROM read_state WHERE conv_key = ?",
                        "DELETE FROM conversations WHERE conv_key = ?"}) {
                    try (PreparedStatement ps = connection.prepareStatement(sql)) {
                        ps.setString(1, conversationKey);
                        ps.executeUpdate();
                    } catch (SQLException ignored) {
                        // 表/行不存在都忽略
                    }
                }
                if (groupId != null) {
                    for (String sql : new String[]{
                            "DELETE FROM group_seen WHERE group_id = ?",
                            "DELETE FROM group_announcement_seen WHERE group_id = ?"}) {
                        try (PreparedStatement ps = connection.prepareStatement(sql)) {
                            ps.setString(1, groupId);
                            ps.executeUpdate();
                        } catch (SQLException ignored) {
                            // 本地清理失败不影响主流程
                        }
                    }
                }
            }
        });
    }

    @Override
    public void close() {
        writer.shutdown();
        try {
            writer.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        synchronized (this) {
            try {
                connection.close();
            } catch (SQLException ignored) {
                // 关闭失败无所谓
            }
        }
    }
}
