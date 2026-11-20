package io.quack.client.store;

import io.quack.common.model.ChatMessage;
import io.quack.common.model.ChatType;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

/**
 * 一个会话（好友私聊 或 群聊）。
 *
 * <p>把界面要用的东西都收在这：标题、在线点、未读数、消息列表。
 * 界面直接绑定这些属性，业务层改数据即自动刷新，不需要手动 refresh。
 */
public class Conversation {

    /** 本地未确认消息的 id 前缀；收到 ACK 后会被替换成服务端 id */
    public static final String LOCAL_ID_PREFIX = "local-";
    /** 发送失败的消息 id 前缀：气泡上显示「发送失败」，不再是永远转圈的「发送中…」 */
    public static final String FAILED_ID_PREFIX = "failed-";

    private final ChatType chatType;
    private final String peerId;
    private final StringProperty title = new SimpleStringProperty("");
    /** 头像 URL（好友/群上传过头像才有） */
    private String avatar;
    /** 是否有人 @ 我（群聊） */
    private boolean mentioned;
    private final BooleanProperty online = new SimpleBooleanProperty(false);
    private final IntegerProperty unread = new SimpleIntegerProperty(0);
    private final ObservableList<ChatMessage> messages = FXCollections.observableArrayList();

    /** 历史消息是否已拉过，避免每次切会话都打一次请求 */
    private boolean historyLoaded;
    /** 请求已发出但还没回来：失败时要能重试 */
    private boolean historyRequested;
    /** 已收到的会话内最大序号：增量同步与已读位点都靠它 */
    private long lastSeq;

    public long lastSeq() {
        return lastSeq;
    }

    public void advanceSeq(long seq) {
        if (seq > lastSeq) {
            lastSeq = seq;
        }
    }

    /**
     * 合并一批消息（同步/补洞）：按 id 与 seq 去重，并按 seq 升序插入。
     *
     * @return 实际新增的条数
     */
    public int mergeMessages(java.util.List<ChatMessage> incoming) {
        if (incoming == null || incoming.isEmpty()) {
            return 0;
        }
        int added = 0;
        for (ChatMessage message : incoming) {
            if (message == null || message.id() == null) {
                continue;
            }
            boolean duplicate = false;
            for (ChatMessage existing : messages) {
                if (message.id().equals(existing.id())
                        || (message.seq() > 0 && message.seq() == existing.seq())) {
                    duplicate = true;
                    break;
                }
            }
            if (!duplicate) {
                messages.add(message);
                added++;
            }
            advanceSeq(message.seq());
        }
        if (added > 0) {
            FXCollections.sort(messages, java.util.Comparator
                    .comparingLong((ChatMessage m) -> m.seq() <= 0 ? Long.MAX_VALUE : m.seq())
                    .thenComparingLong(ChatMessage::ts));
        }
        return added;
    }

    public Conversation(ChatType chatType, String peerId, String title, boolean online) {
        this.chatType = chatType;
        this.peerId = peerId;
        this.title.set(title);
        this.online.set(online);
    }

    public String key() {
        return chatType.name() + ":" + peerId;
    }

    public ChatType getChatType() {
        return chatType;
    }

    public String getPeerId() {
        return peerId;
    }

    public StringProperty titleProperty() {
        return title;
    }

    public String avatar() {
        return avatar;
    }

    public void setAvatar(String avatar) {
        this.avatar = avatar;
    }

    /**
     * 发送者改了头像/昵称：把历史消息里的快照就地替换。
     * 消息是 record，只能整条替换；数量在本地缓存上限内，代价可接受。
     */
    public void updateSenderSnapshot(String senderId, String senderName, String senderAvatar) {
        if (senderId == null) {
            return;
        }
        for (int i = 0; i < messages.size(); i++) {
            ChatMessage message = messages.get(i);
            if (!senderId.equals(message.from())) {
                continue;
            }
            boolean nameChanged = senderName != null && !senderName.equals(message.fromName());
            boolean avatarChanged = !java.util.Objects.equals(senderAvatar, message.fromAvatar());
            if (!nameChanged && !avatarChanged) {
                continue;
            }
            messages.set(i, new ChatMessage(message.id(), message.clientMsgId(), message.from(),
                    senderName == null ? message.fromName() : senderName,
                    message.to(), message.chatType(), message.msgType(), message.content(),
                    message.ts(), senderAvatar, message.seq(), message.edited()));
        }
    }
    /** 最后一条消息的时间，用于把有新消息的会话排到最上面 */
    public long lastActivityTs() {
        return messages.isEmpty() ? 0 : messages.get(messages.size() - 1).ts();
    }

    public boolean isMentioned() {
        return mentioned;
    }

    public void setMentioned(boolean mentioned) {
        this.mentioned = mentioned;
    }

    public String getTitle() {
        return title.get();
    }

    public void setTitle(String value) {
        title.set(value);
    }

    public BooleanProperty onlineProperty() {
        return online;
    }

    public boolean isOnline() {
        return online.get();
    }

    public void setOnline(boolean value) {
        online.set(value);
    }

    public IntegerProperty unreadProperty() {
        return unread;
    }

    public int getUnread() {
        return unread.get();
    }

    public void incrementUnread() {
        unread.set(unread.get() + 1);
    }

    /** 用服务端权威值覆盖未读数（登录/换设备/已读同步） */
    public void setUnread(int value) {
        unread.set(Math.max(0, value));
    }

    public void clearUnread() {
        unread.set(0);
    }

    public ObservableList<ChatMessage> messages() {
        return messages;
    }

    public boolean isHistoryLoaded() {
        return historyLoaded;
    }

    public void markHistoryLoaded() {
        this.historyLoaded = true;
        this.historyRequested = false;
    }

    public boolean isHistoryRequested() {
        return historyRequested;
    }

    public void setHistoryRequested(boolean value) {
        this.historyRequested = value;
    }

    /**
     * 把一条“发送中”的消息标成发送失败。
     *
     * <p>不改 id 的语义（仍然唯一），只换前缀，界面据此显示「发送失败」；
     * 之后如果用户重发，会生成新的 clientMsgId，互不影响。
     */
    public boolean markFailed(String clientMsgId) {
        if (clientMsgId == null) {
            return false;
        }
        for (int i = 0; i < messages.size(); i++) {
            ChatMessage message = messages.get(i);
            if (clientMsgId.equals(message.clientMsgId())
                    && message.id() != null && message.id().startsWith(LOCAL_ID_PREFIX)) {
                messages.set(i, new ChatMessage(FAILED_ID_PREFIX + clientMsgId, message.clientMsgId(),
                        message.from(), message.fromName(), message.to(), message.chatType(),
                        message.msgType(), message.content(), message.ts(), message.fromAvatar()));
                return true;
            }
        }
        return false;
    }

    /**
     * 收到服务端 ACK：把本地“发送中”的消息替换成带服务端 id 与时间戳的正式消息。
     *
     * <p>替换而不是修改，是因为 ChatMessage 是不可变 record。
     * ObservableList.set() 会触发单元格重绘，气泡上的“发送中”就自动消失了。
     *
     * @return 是否找到并替换
     */
    public ChatMessage markDelivered(String clientMsgId, String messageId, long ts, long seq) {
        if (clientMsgId == null) {
            return null;
        }
        for (int i = 0; i < messages.size(); i++) {
            ChatMessage message = messages.get(i);
            if (clientMsgId.equals(message.clientMsgId())
                    && message.id() != null && message.id().startsWith(LOCAL_ID_PREFIX)) {
                ChatMessage delivered = new ChatMessage(messageId, message.clientMsgId(), message.from(),
                        message.fromName(), message.to(), message.chatType(), message.msgType(),
                        message.content(), ts, message.fromAvatar(), seq);
                messages.set(i, delivered);
                advanceSeq(seq);
                return delivered;
            }
        }
        return null;
    }

    @Override
    public String toString() {
        return key();
    }
}
