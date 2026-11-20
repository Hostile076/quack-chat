package io.quack.client.store;

import io.quack.common.dto.FriendRequestsResp;
import io.quack.common.dto.LoginResp;
import io.quack.common.dto.SendMessageAck;
import io.quack.common.model.ChatGroup;
import io.quack.common.model.ChatMessage;
import io.quack.common.model.ChatType;
import io.quack.common.model.Friend;
import io.quack.common.model.FriendRequest;
import io.quack.common.model.User;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 客户端本地状态（内存版）。
 *
 * <p>这里刻意只用 ObservableList + Property，界面监听它、它不监听界面，
 * 数据流是单向的：网络事件 -&gt; store -&gt; 界面。
 */
public class ClientStore {

    private final ObservableList<Conversation> conversations = FXCollections.observableArrayList();
    private final Map<String, Conversation> byKey = new HashMap<>();
    private final ConversationPrefs prefs = new ConversationPrefs();
    /** 会话 key -> (reader -> 已读时间戳) */
    private final Map<String, Map<String, Long>> readAt = new java.util.concurrent.ConcurrentHashMap<>();
    /** 会话 key -> (reader -> 已读序号)，权威字段 */
    private final Map<String, Map<String, Long>> readSeq = new java.util.concurrent.ConcurrentHashMap<>();

    private final StringProperty myName = new SimpleStringProperty("");
    private final StringProperty myAvatar = new SimpleStringProperty("");
    /** 当前账号的本地库：聊天记录/游标/草稿/已读（打不开时为 null，功能降级） */
    private LocalStore local;
    /** 别人申请加我（待处理） */
    private final ObservableList<FriendRequest> incomingRequests = FXCollections.observableArrayList();
    /** 我发出的申请 */
    private final ObservableList<FriendRequest> outgoingRequests = FXCollections.observableArrayList();
    private User me;

    /** 登录成功后重建全部会话；刻意复用同一个 ObservableList 实例，重连后界面无需重建 */
    public void reset(LoginResp resp) {
        conversations.clear();
        byKey.clear();
        incomingRequests.clear();
        outgoingRequests.clear();
        // 已读位点是“某账号视角下的数据”，换号后必须清掉，否则会用上一个账号的已读状态渲染
        readAt.clear();
        readSeq.clear();
        this.me = resp.user();
        this.myName.set(resp.user().displayName());
        this.myAvatar.set(resp.user().avatar() == null ? "" : resp.user().avatar());

        // 每个账号一个本地库：换号时先关掉上一个
        if (local != null) {
            local.close();
            local = null;
        }
        local = LocalStore.open(resp.user().qcId());

        for (Friend friend : resp.friends()) {
            Conversation conversation = new Conversation(ChatType.PRIVATE, friend.userId(),
                    friend.displayName(), friend.online());
            conversation.setAvatar(friend.avatar());
            if (local != null) {
                conversation.advanceSeq(local.lastSeq(conversation.key()));
            }
            add(conversation);
        }
        for (ChatGroup group : resp.groups()) {
            Conversation conversation = new Conversation(ChatType.GROUP, group.id(), group.name(), true);
            conversation.setAvatar(group.avatar());
            if (local != null) {
                conversation.advanceSeq(local.lastSeq(conversation.key()));
            }
            add(conversation);
        }
        // 置顶的会话排在前面
        resort();

        // 服务端未读数是权威值（换设备/重装后也能恢复角标）
        if (resp.unread() != null) {
            resp.unread().forEach((key, count) -> {
                Conversation conversation = byKey.get(key);
                if (conversation != null) {
                    conversation.setUnread(count == null ? 0 : count);
                }
            });
        }
        persistConversations();
    }

    /** 服务端同步未读数（已读后同账号其它设备） */
    public void setUnread(String conversationKey, int count) {
        Conversation conversation = byKey.get(conversationKey);
        if (conversation != null) {
            conversation.setUnread(count);
            persistConversations();
        }
    }

    /**
     * 离线登录：用本地缓存的会话列表重建 store（不连服务端）。
     *
     * @return 是否至少有一个会话/缓存可用
     */
    public boolean restoreOffline(User me, List<LocalStore.OfflineConversation> cached) {
        conversations.clear();
        byKey.clear();
        incomingRequests.clear();
        outgoingRequests.clear();
        readAt.clear();
        readSeq.clear();
        this.me = me;
        this.myName.set(me.displayName());
        this.myAvatar.set(me.avatar() == null ? "" : me.avatar());
        if (local != null) {
            local.close();
        }
        local = LocalStore.open(me.qcId());
        for (LocalStore.OfflineConversation conversation : cached) {
            Conversation restored = new Conversation(conversation.chatType(), conversation.peerId(),
                    conversation.title(), true);
            restored.setAvatar(conversation.avatar());
            restored.advanceSeq(conversation.lastSeq());
            restored.setUnread(conversation.unread());
            add(restored);
        }
        resort();
        return !conversations.isEmpty();
    }

    /** 把当前会话列表（标题/头像/lastSeq/未读）写回本地库，供离线登录使用 */
    public void persistConversations() {
        if (local == null) {
            return;
        }
        List<LocalStore.OfflineConversation> snapshot = new java.util.ArrayList<>();
        for (Conversation conversation : conversations) {
            snapshot.add(new LocalStore.OfflineConversation(conversation.key(),
                    conversation.getChatType(), conversation.getPeerId(), conversation.getTitle(),
                    conversation.avatar(), conversation.lastSeq(), conversation.getUnread()));
        }
        local.saveConversations(snapshot);
    }

    /** 置顶/取消置顶后重新排序（ObservableList 的排序会触发列表刷新） */
    public void resort() {
        // 置顶优先；其余按最近一条消息排序，新消息会自然冒到最上面
        conversations.sort(Comparator
                .comparing((Conversation c) -> !prefs.isPinned(c.key()))
                .thenComparing(Comparator.comparingLong(Conversation::lastActivityTs).reversed())
                .thenComparing(Conversation::getTitle));
    }

    private void add(Conversation conversation) {
        if (byKey.putIfAbsent(conversation.key(), conversation) == null) {
            conversations.add(conversation);
        }
    }

    /** 加入群成功后确保会话出现在列表里 */
    public Conversation ensureGroupConversation(io.quack.common.model.GroupDetail detail) {
        if (detail == null) {
            return null;
        }
        String key = ChatType.GROUP.name() + ":" + detail.id();
        Conversation existing = byKey.get(key);
        if (existing != null) {
            existing.setTitle(detail.name());
            existing.setAvatar(detail.avatar());
            return existing;
        }
        Conversation conversation = new Conversation(ChatType.GROUP, detail.id(), detail.name(), true);
        conversation.setAvatar(detail.avatar());
        add(conversation);
        resort();
        persistConversations();
        return conversation;
    }

    /**
     * 加好友成功（自己发起的回包，或被别人加的推送）。
     *
     * @return 对应的会话（已存在则复用）
     */
    public Conversation addFriend(Friend friend) {
        if (friend == null || friend.userId() == null) {
            return null;
        }
        Conversation existing = byKey.get(ChatType.PRIVATE.name() + ":" + friend.userId());
        if (existing != null) {
            existing.setOnline(friend.online());
            existing.setTitle(friend.displayName());
            if (friend.avatar() != null) {
                existing.setAvatar(friend.avatar());
            }
            persistConversations();
            return existing;
        }
        Conversation created = new Conversation(ChatType.PRIVATE, friend.userId(),
                friend.displayName(), friend.online());
        created.setAvatar(friend.avatar());
        add(created);
        persistConversations();
        return created;
    }

    public User me() {
        return me;
    }

    public ObservableList<Conversation> conversations() {
        return conversations;
    }

    public Conversation conversationOf(ChatMessage message) {
        if (message.chatType() == ChatType.PRIVATE) {
            String myId = me == null ? null : me.id();
            String peerId = message.from().equals(myId) ? message.to() : message.from();
            Conversation existing = byKey.get(ChatType.PRIVATE.name() + ":" + peerId);
            if (existing != null) {
                return existing;
            }
            Conversation created = new Conversation(ChatType.PRIVATE, peerId,
                    message.fromName() == null ? peerId : message.fromName(), true);
            add(created);
            return created;
        }
        Conversation existing = byKey.get(ChatType.GROUP.name() + ":" + message.to());
        if (existing != null) {
            return existing;
        }
        Conversation created = new Conversation(ChatType.GROUP, message.to(), "群聊 " + message.to(), true);
        add(created);
        return created;
    }

    /**
     * 应用一条推送消息。
     *
     * <p>按服务端消息 id 去重：断线重连时离线补发与实时推送可能产生重复。
     */
    public Conversation applyPush(ChatMessage message) {
        Conversation conversation = conversationOf(message);
        // 按服务端消息 id / 会话内 seq 去重：离线补发、同步与实时推送可能重叠
        boolean duplicated = conversation.messages().stream()
                .anyMatch(m -> (m.id() != null && m.id().equals(message.id()))
                        || (message.seq() > 0 && m.seq() == message.seq()));
        if (!duplicated) {
            conversation.messages().add(message);
            // 免打扰的会话不累加未读角标
            if (!prefs.isMuted(conversation.key())) {
                conversation.incrementUnread();
            }
        }
        conversation.advanceSeq(message.seq());
        if (local != null) {
            local.saveMessage(conversation.key(), message);
            local.saveLastSeq(conversation.key(), conversation.lastSeq());
        }
        persistConversations();
        return conversation;
    }

    /** 合并一次增量同步结果，并按 seq 排好序 */
    public int applySync(Conversation conversation, java.util.List<ChatMessage> messages) {
        if (conversation == null) {
            return 0;
        }
        int added = conversation.mergeMessages(messages);
        if (local != null) {
            local.saveMessages(conversation.key(), messages);
            local.saveLastSeq(conversation.key(), conversation.lastSeq());
        }
        persistConversations();
        if (added > 0) {
            resort();
        }
        return added;
    }

    /** 撤回推送 / 撤回回执：把会话里的原消息替换成“已撤回” */
    public void applyRecall(ChatMessage recalled) {
        if (recalled == null || recalled.id() == null) {
            return;
        }
        Conversation conversation = conversationOf(recalled);
        for (int i = 0; i < conversation.messages().size(); i++) {
            if (recalled.id().equals(conversation.messages().get(i).id())) {
                conversation.messages().set(i, recalled);
                if (local != null) {
                    local.saveMessage(conversation.key(), recalled);
                }
                return;
            }
        }
    }

    /** 编辑推送 / 编辑回执：替换会话里的原消息 */
    public void applyEdit(ChatMessage edited) {
        if (edited == null || edited.id() == null) {
            return;
        }
        Conversation conversation = conversationOf(edited);
        for (int i = 0; i < conversation.messages().size(); i++) {
            if (edited.id().equals(conversation.messages().get(i).id())) {
                conversation.messages().set(i, edited);
                if (local != null) {
                    local.saveMessage(conversation.key(), edited);
                }
                return;
            }
        }
    }

    /** 按会话键查找，找不到返回 null（用于历史消息回填时定位会话） */
    public Conversation find(ChatType chatType, String peerId) {
        return byKey.get(chatType.name() + ":" + peerId);
    }

    public void updatePresence(String userId, boolean online) {
        Conversation conversation = byKey.get(ChatType.PRIVATE.name() + ":" + userId);
        if (conversation != null) {
            conversation.setOnline(online);
        }
    }

    /** 收到 ACK：把“发送中”替换成正式消息（可能在任意会话里，逐个找） */
    public void markDelivered(SendMessageAck ack) {
        if (ack == null || ack.clientMsgId() == null) {
            return;
        }
        for (Conversation conversation : conversations) {
            ChatMessage delivered = conversation.markDelivered(
                    ack.clientMsgId(), ack.messageId(), ack.ts(), ack.seq());
            if (delivered != null) {
                if (local != null) {
                    local.saveMessage(conversation.key(), delivered);
                    local.saveLastSeq(conversation.key(), conversation.lastSeq());
                }
                return;
            }
        }
    }

    // ------------------------------------------------------------------
    // 好友申请
    // ------------------------------------------------------------------

    public ObservableList<FriendRequest> incomingRequests() {
        return incomingRequests;
    }

    public ObservableList<FriendRequest> outgoingRequests() {
        return outgoingRequests;
    }

    public void setRequests(FriendRequestsResp resp) {
        incomingRequests.setAll(resp.incoming() == null ? java.util.List.of() : resp.incoming());
        outgoingRequests.setAll(resp.outgoing() == null ? java.util.List.of() : resp.outgoing());
    }

    /** 实时收到新申请：去重后插到最前面 */
    public void addIncomingRequest(FriendRequest request) {
        if (request == null) {
            return;
        }
        boolean exists = incomingRequests.stream().anyMatch(r -> r.id().equals(request.id()));
        if (!exists) {
            incomingRequests.add(0, request);
        }
    }

    public void removeIncomingRequest(String requestId) {
        incomingRequests.removeIf(r -> r.id().equals(requestId));
    }

    public void removeOutgoingRequest(String requestId) {
        outgoingRequests.removeIf(r -> r.id().equals(requestId));
    }

    public Optional<FriendRequest> outgoingById(String requestId) {
        return outgoingRequests.stream().filter(r -> r.id().equals(requestId)).findFirst();
    }

    /** 当前账号的本地库（可能为 null：打不开时降级为纯内存） */
    public LocalStore local() {
        return local;
    }

    /** 群公告是否已经“收到”过（内容变了会重新弹） */
    public boolean isAnnouncementSeen(String groupId, String announcement) {
        if (local == null || groupId == null || announcement == null) {
            return false;
        }
        return LocalStore.hashAnnouncement(announcement).equals(local.announcementSeen(groupId));
    }

    public void markAnnouncementSeen(String groupId, String announcement) {
        if (local != null && groupId != null) {
            local.markAnnouncementSeen(groupId, LocalStore.hashAnnouncement(announcement));
        }
    }

    /** 多条公告：按单条哈希判断是否已读 */
    public boolean isAnnouncementHashSeen(String groupId, String hash) {
        return local != null && groupId != null && local.announcementHashSeen(groupId, hash);
    }

    public void markAnnouncementHashSeen(String groupId, String hash) {
        if (local != null && groupId != null) {
            local.markAnnouncementHashSeen(groupId, hash);
        }
    }

    /** 保存输入草稿（切换会话/退出时调用） */
    public void saveDraft(String conversationKey, String text) {
        if (local != null && conversationKey != null) {
            local.saveDraft(conversationKey, text);
        }
    }

    /** 打开会话时先用本地缓存渲染，返回补进来的条数 */
    public int loadLocalMessages(Conversation conversation, int limit) {
        if (local == null || conversation == null) {
            return 0;
        }
        int added = conversation.mergeMessages(local.loadMessages(conversation.key(), limit));
        conversation.advanceSeq(local.lastSeq(conversation.key()));
        local.readSeqs(conversation.key()).forEach((reader, seq) -> {
            if (seq > 0) {
                readSeq.computeIfAbsent(conversation.key(),
                        k -> new java.util.concurrent.ConcurrentHashMap<>()).merge(reader, seq, Math::max);
            }
        });
        return added;
    }

    public int pendingRequestCount() {
        return incomingRequests.size();
    }

    public ConversationPrefs prefs() {
        return prefs;
    }

    public StringProperty myNameProperty() {
        return myName;
    }

    /** 改完昵称后同步界面显示（服务端已经改完，这里只更新本地快照） */
    public void renameMe(String nickname) {
        if (me == null || nickname == null || nickname.isBlank()) {
            return;
        }
        this.me = new User(me.qcId(), me.username(), nickname, me.avatar());
        this.myName.set(nickname);
    }

    /** 保存资料后同步本地身份（用户名、昵称、头像都会变） */
    public void applyProfile(io.quack.common.model.UserProfile profile) {
        if (profile == null) {
            return;
        }
        String name = profile.displayName();
        this.me = new User(profile.qcId(), profile.username(), name, profile.avatar());
        this.myName.set(name);
        this.myAvatar.set(profile.avatar() == null ? "" : profile.avatar());
    }

    /**
     * 有人改了资料（昵称/头像/用户名）：
     * <ol>
     *   <li>本人：同步本地身份，左下角头像立即变；</li>
     *   <li>好友：更新会话标题/头像；</li>
     *   <li>所有会话：把历史消息里的发送者快照一起换掉，否则聊天气泡头像永远是旧的。</li>
     * </ol>
     */
    public void applyUserUpdate(io.quack.common.model.User user) {
        if (user == null) {
            return;
        }
        if (me != null && me.id().equals(user.id())) {
            this.me = user;
            this.myName.set(user.displayName());
            this.myAvatar.set(user.avatar() == null ? "" : user.avatar());
        }
        Conversation conversation = byKey.get(ChatType.PRIVATE.name() + ":" + user.id());
        if (conversation != null) {
            conversation.setTitle(user.displayName());
            conversation.setAvatar(user.avatar());
            persistConversations();
        }
        // 消息里存的是发送者快照，头像/昵称变了要就地替换
        for (Conversation item : conversations) {
            item.updateSenderSnapshot(user.id(), user.displayName(), user.avatar());
        }
    }

    /** 记录已读通知：私聊用 readerId，群聊用 peerId 归档 */
    public void applyReadNotify(io.quack.common.dto.ReadNotify notify) {
        if (notify == null) {
            return;
        }
        String key = "GROUP".equals(notify.chatType())
                ? "GROUP:" + notify.peerId()
                : "PRIVATE:" + notify.readerId();
        readAt.computeIfAbsent(key, k -> new java.util.concurrent.ConcurrentHashMap<>())
                .merge(notify.readerId(), notify.ts(), Math::max);
        if (notify.seq() > 0) {
            readSeq.computeIfAbsent(key, k -> new java.util.concurrent.ConcurrentHashMap<>())
                    .merge(notify.readerId(), notify.seq(), Math::max);
            if (local != null) {
                local.saveReadSeq(key, notify.readerId(), notify.seq());
            }
        }
    }

    /** 某人读到的序号（0 = 没读过） */
    public long readSeq(String conversationKey, String readerId) {
        return readSeq.getOrDefault(conversationKey, Map.of()).getOrDefault(readerId, 0L);
    }

    /** 某会话里所有读者的已读序号 */
    public Map<String, Long> readSeqMap(String conversationKey) {
        return readSeq.getOrDefault(conversationKey, Map.of());
    }

    public long readTs(String conversationKey, String readerId) {
        return readAt.getOrDefault(conversationKey, Map.of()).getOrDefault(readerId, 0L);
    }

    public Map<String, Long> readMap(String conversationKey) {
        return readAt.getOrDefault(conversationKey, Map.of());
    }

    /** 备注变更：会话标题改成备注（没有备注就用对方昵称） */
    public void applyFriendRemark(io.quack.common.model.Friend friend) {
        if (friend == null) {
            return;
        }
        Conversation conversation = byKey.get(ChatType.PRIVATE.name() + ":" + friend.userId());
        if (conversation != null) {
            conversation.setTitle(friend.displayName());
            if (friend.avatar() != null) {
                conversation.setAvatar(friend.avatar());
            }
            persistConversations();
        }
    }

    public StringProperty myAvatarProperty() {
        return myAvatar;
    }

    public void removeConversation(Conversation conversation) {
        if (conversation == null) {
            return;
        }
        conversations.remove(conversation);
        byKey.remove(conversation.key());
        if (local != null) {
            local.deleteConversation(conversation.key());
        }
    }

    public void clearMessages(Conversation conversation) {
        if (conversation != null) {
            conversation.messages().clear();
        }
    }
}
