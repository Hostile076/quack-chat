package io.quack.client;

import io.quack.client.media.AudioCall;
import io.quack.client.net.FileUploader;
import io.quack.client.net.WsClient;
import io.quack.client.store.AccountStore;
import io.quack.client.store.ClientStore;
import io.quack.client.store.Conversation;
import io.quack.client.ui.AppWindow;
import io.quack.client.ui.LoginView;
import io.quack.client.ui.MainView;
import io.quack.common.dto.CallReq;
import io.quack.common.dto.EssenceListResp;
import io.quack.common.dto.FriendHandleReq;
import io.quack.common.dto.FriendHandleResp;
import io.quack.common.dto.FriendRequestReq;
import io.quack.common.dto.FriendRequestResp;
import io.quack.common.dto.FriendRequestsResp;
import io.quack.common.dto.GroupDetailReq;
import io.quack.common.dto.GroupDetailResp;
import io.quack.common.dto.GroupQuitReq;
import io.quack.common.dto.GroupUpdateReq;
import io.quack.common.dto.HistoryReq;
import io.quack.common.dto.HistoryResp;
import io.quack.common.dto.GroupInviteReq;
import io.quack.common.dto.GroupMemberOpReq;
import io.quack.common.dto.LikeReq;
import io.quack.common.dto.LikeResp;
import io.quack.common.dto.LoginReq;
import io.quack.common.dto.LoginResp;
import io.quack.common.dto.MomentLikeReq;
import io.quack.common.dto.MomentListResp;
import io.quack.common.dto.MomentPublishReq;
import io.quack.common.dto.MomentReq;
import io.quack.common.dto.MomentResp;
import io.quack.common.dto.PresenceNotify;
import io.quack.common.dto.ProfileReq;
import io.quack.common.dto.ProfileResp;
import io.quack.common.dto.RegisterReq;
import io.quack.common.dto.RegisterResp;
import io.quack.common.dto.SendMessageAck;
import io.quack.common.dto.SendMessageReq;
import io.quack.common.dto.TypingNotify;
import io.quack.common.dto.UpdateProfileReq;
import io.quack.common.dto.UserSearchReq;
import io.quack.common.dto.UserSearchResp;
import io.quack.common.dto.CallAudioReq;
import io.quack.common.model.CallSignal;
import io.quack.common.model.ChatMessage;
import io.quack.common.model.ChatType;
import io.quack.common.model.Friend;
import io.quack.common.model.FriendRequest;
import io.quack.common.model.GroupDetail;
import io.quack.common.model.Moment;
import io.quack.common.model.MomentComment;
import io.quack.common.model.MomentMedia;
import io.quack.common.model.User;
import io.quack.common.model.UserProfile;
import io.quack.common.model.MsgType;
import io.quack.common.protocol.Cmd;
import io.quack.common.protocol.Envelope;
import javafx.application.Platform;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 客户端“应用层”：把网络、状态、界面粘在一起，但不掺业务细节。
 *
 * <p>线程模型只有一条规则：WebSocket 回调线程不许直接操作 JavaFX 控件，
 * 一律走 {@link Platform#runLater}。
 */
public class AppContext {

    private static final Logger log = LoggerFactory.getLogger(AppContext.class);

    private final AppWindow window;
    private final WsClient ws = new WsClient();
    private final ClientStore store = new ClientStore();
    private final AccountStore accounts = new AccountStore();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "app-reconnect");
        t.setDaemon(true);
        return t;
    });

    private volatile User me;
    private volatile String serverUrl;
    private volatile LoginReq credentials;
    private volatile String pendingPassword;
    /** 登录令牌：HTTP 上传等接口用；只放内存 */
    private volatile String token;
    private final java.util.List<ChatMessage> pendingPushes = new java.util.ArrayList<>();
    private AudioCall audioCall;
    private String audioCallId;
    private final ConcurrentLinkedQueue<String> outboundAudio = new ConcurrentLinkedQueue<>();
    private javafx.animation.Timeline audioSender;
    private volatile boolean flushScheduled;
    private volatile int reconnectAttempt;
    private volatile boolean reconnectScheduled;
    private volatile boolean closing;
    /** 离线模式：只读本地缓存，不连服务端 */
    private volatile boolean offline;
    private final javafx.beans.property.IntegerProperty pendingGroupRequests =
            new javafx.beans.property.SimpleIntegerProperty(0);
    private MainView mainView;

    public AppContext(AppWindow window) {
        this.window = window;
        ws.setListener(new WsClient.Listener() {
            @Override
            public void onOpen() {
                log.info("WebSocket 已连接 {}", serverUrl);
            }

            @Override
            public void onEnvelope(Envelope envelope) {
                handleEnvelope(envelope);
            }

            @Override
            public void onClosed(int code, String reason) {
                handleClosed(code, reason);
            }

            @Override
            public void onError(Throwable error) {
                log.warn("WebSocket 异常: {}", error.getMessage());
            }
        });
        // 兜底：发出的消息 10 秒还没 ACK（对端/网络问题），标成发送失败，避免永远“发送中…”
        scheduler.scheduleWithFixedDelay(() -> Platform.runLater(this::failStalePending),
                5, 5, TimeUnit.SECONDS);
    }

    public ClientStore store() {
        return store;
    }

    /** 本地偏好变化后让主界面的会话过滤重新生效（例如“显示已隐藏会话”） */
    public void refreshMainFilter() {
        if (mainView != null) {
            Platform.runLater(mainView::refreshFilter);
        }
    }

    public AppWindow window() {
        return window;
    }

    public AccountStore accounts() {
        return accounts;
    }

    public WsClient ws() {
        return ws;
    }

    public User me() {
        return me;
    }

    public String serverUrl() {
        return serverUrl;
    }

    /** 登录令牌（HTTP 上传用），未登录时为 null */
    public String token() {
        return token;
    }

    /**
     * 上传文件（统一入口）：token 缺失或过期时自动用本机记住的账号重新登录一次再重试。
     * 这样服务端重启导致内存 token 失效时，头像/图片/视频上传不会直接 401。
     */
    public CompletableFuture<FileUploader.Uploaded> uploadFile(Path file) {
        return tokenForUpload()
                .thenCompose(value -> FileUploader.upload(serverUrl, file, value))
                .exceptionallyCompose(error -> {
                    if (isUnauthorized(error) && credentials != null) {
                        token = null;
                        return tokenForUpload()
                                .thenCompose(value -> FileUploader.upload(serverUrl, file, value));
                    }
                    return CompletableFuture.failedFuture(error);
                });
    }

    private CompletableFuture<String> tokenForUpload() {
        if (token != null && !token.isBlank()) {
            return CompletableFuture.completedFuture(token);
        }
        return requestNewToken();
    }

    /** 用本机记住的账号经 HTTP /api/token 刷新上传令牌（不重建登录界面） */
    private CompletableFuture<String> requestNewToken() {
        if (credentials == null) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("登录状态已失效，请重新登录"));
        }
        try {
            String base = FileUploader.httpBase(serverUrl);
            byte[] body = io.quack.common.protocol.JsonCodec.toJson(credentials)
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
            java.net.http.HttpRequest request = java.net.http.HttpRequest
                    .newBuilder(java.net.URI.create(base + "/api/token"))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofByteArray(body))
                    .build();
            return java.net.http.HttpClient.newHttpClient()
                    .sendAsync(request, java.net.http.HttpResponse.BodyHandlers.ofString())
                    .thenApply(response -> {
                        if (response.statusCode() != 200) {
                            throw new IllegalStateException("登录状态已失效，请重新登录");
                        }
                        try {
                            @SuppressWarnings("unchecked")
                            java.util.Map<String, Object> json = io.quack.common.protocol.JsonCodec
                                    .mapper().readValue(response.body(), java.util.Map.class);
                            String refreshed = String.valueOf(json.get("token"));
                            if (refreshed == null || refreshed.isBlank() || "null".equals(refreshed)) {
                                throw new IllegalStateException("登录状态已失效，请重新登录");
                            }
                            this.token = refreshed;
                            return refreshed;
                        } catch (java.io.IOException error) {
                            throw new IllegalStateException("刷新登录状态失败", error);
                        }
                    });
        } catch (RuntimeException error) {
            return CompletableFuture.failedFuture(error);
        }
    }

    private static boolean isUnauthorized(Throwable error) {
        Throwable current = error;
        while (current != null) {
            String message = current.getMessage();
            if (message != null && message.contains("401")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    // ------------------------------------------------------------------
    // 登录 / 注册 / 切换账号
    // ------------------------------------------------------------------

    public CompletableFuture<Void> login(String url, String account, String password) {
        this.serverUrl = url;
        accounts.rememberServer(url);
        return loginWith(account, password);
    }

    private CompletableFuture<Void> loginWith(String account, String password) {
        this.credentials = new LoginReq(account, password, accounts.deviceId());
        this.pendingPassword = password;
        return ensureConnected()
                .thenCompose(ignored -> ws.request(Cmd.LOGIN, credentials, Duration.ofSeconds(10)))
                .thenAccept(this::applyLoginResponse);
    }

    /** 注册：成功后用分配到的 QC 号直接登录，并把 QC 号返回给界面展示 */
    public CompletableFuture<User> register(String url, String username, String password) {
        this.serverUrl = url;
        accounts.rememberServer(url);
        return ensureConnected()
                .thenCompose(ignored -> ws.request(Cmd.REGISTER,
                        new RegisterReq(username, password), Duration.ofSeconds(10)))
                .thenCompose(envelope -> {
                    if (envelope.code() != 0) {
                        throw new IllegalStateException(envelope.msg() == null ? "注册失败" : envelope.msg());
                    }
                    RegisterResp resp = envelope.dataAs(RegisterResp.class);
                    if (resp == null || resp.user() == null) {
                        throw new IllegalStateException("注册响应缺少账号信息");
                    }
                    User created = resp.user();
                    return loginWith(created.id(), password).thenApply(ignored -> created);
                });
    }

    /** 已连着就复用，避免重复握手 */
    private CompletableFuture<Void> ensureConnected() {
        if (ws.isOpen()) {
            return CompletableFuture.completedFuture(null);
        }
        return ws.connect(serverUrl);
    }

    private void applyLoginResponse(Envelope envelope) {
        if (envelope.code() != 0) {
            throw new IllegalStateException(envelope.msg() == null ? "登录失败" : envelope.msg());
        }
        LoginResp resp = envelope.dataAs(LoginResp.class);
        if (resp == null || resp.user() == null) {
            throw new IllegalStateException("登录响应缺少用户信息");
        }

        this.offline = false;
        io.quack.client.net.MediaUrls.setServer(serverUrl);
        this.me = resp.user();
        this.reconnectAttempt = 0;
        this.token = resp.token();
        accounts.remember(resp.user(), pendingPassword);

        // store 是 ObservableList，必须在 FX 线程改；而且重连后旧 ChatPane 里持有的
        // Conversation 对象已经过期（store.reset 会重新创建），所以整块界面一起重建，
        // 否则会出现“列表是新的、点进去没有新消息”的问题。
        Platform.runLater(() -> {
            if (mainView != null) {
                mainView.dispose();
                mainView = null;
            }
            store.reset(resp);
            showMainView();
        });
        loadFriendRequests();
        refreshGroupJoinBadge();
    }

    private void showMainView() {
        if (mainView == null) {
            mainView = new MainView(this);
            window.setTitleBarVisible(true);
            // 登录后允许最大化（登录页不给最大化）
            window.setMaximizable(true);
            // 主界面恢复正常最小尺寸
            window.setMinSize(720, 520);
            window.setContent(mainView);
            window.ensureAtLeast(1000, 680);
        }
        window.setTitle("Quack Chat - " + (me == null ? "" : me.displayName()) + "（" + (me == null ? "" : me.qcId()) + "）");
        mainView.onConnectionRestored();
    }

    /**
     * 离线登录：用本机已登录过的账号 + 本地缓存进入主界面（不连服务端）。
     *
     * <p>密码只在本机用 PBKDF2 校验值验证；离线模式只读，不能发消息。
     */
    public CompletableFuture<Void> loginOffline(long qcId, String password) {
        io.quack.client.store.AccountStore.SavedAccount account =
                accounts.byQcId(qcId).orElse(null);
        if (account == null) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("本机没有这个账号，请先在线登录一次"));
        }
        if (!account.canLoginOffline()) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("该账号还没有在本机在线登录过，无法离线登录"));
        }
        if (!accounts.verifyOffline(qcId, password)) {
            return CompletableFuture.failedFuture(new IllegalStateException("密码不正确"));
        }
        io.quack.client.store.LocalStore probe = io.quack.client.store.LocalStore.open(qcId);
        java.util.List<io.quack.client.store.LocalStore.OfflineConversation> cached =
                probe == null ? java.util.List.of() : probe.loadConversations();
        if (probe != null) {
            probe.close();
        }
        if (cached.isEmpty()) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("本机还没有缓存会话，请先在线登录一次"));
        }
        User user = new User(qcId, account.username(), account.nickname(), account.avatar());
        this.offline = true;
        this.credentials = null;
        this.token = null;
        this.me = user;
        this.reconnectAttempt = 0;
        Platform.runLater(() -> {
            if (mainView != null) {
                mainView.dispose();
                mainView = null;
            }
            store.restoreOffline(user, cached);
            showMainView();
            if (mainView != null) {
                mainView.showOfflineBanner();
            }
        });
        return CompletableFuture.completedFuture(null);
    }

    public boolean isOffline() {
        return offline;
    }

    /** 离线模式横幅点击：回到登录页，可重新在线登录 */
    public void leaveOffline() {
        if (!offline) {
            return;
        }
        offline = false;
        showLogin(String.valueOf(me == null ? 0 : me.qcId()));
    }

    private void rejectOffline() {
        if (mainView != null) {
            mainView.showOfflineBanner();
        }
    }

    /** 切换到这台设备上登录过的另一个 QC 号 */
    public void switchAccount(long qcId) {
        if (offline) {
            showLogin(String.valueOf(qcId));
            return;
        }
        if (me != null && me.qcId() == qcId) {
            return;
        }
        String password = accounts.passwordOf(qcId).orElse(null);
        if (password == null) {
            // 重启后才有的账号：密码不落盘，只能回登录页手输（QC 号已填好）
            showLogin(String.valueOf(qcId));
            return;
        }
        disconnectSilently();
        login(serverUrl, String.valueOf(qcId), password).exceptionally(error -> {
            log.warn("切换账号失败: {}", error.getMessage());
            showLogin(String.valueOf(qcId));
            return null;
        });
    }

    public void logout() {
        disconnectSilently();
        showLogin(String.valueOf(me == null ? 0 : me.qcId()));
    }

    private void showLogin(String prefillAccount) {
        disconnectSilently();
        this.me = null;
        Platform.runLater(() -> {
            if (mainView != null) {
                mainView.dispose();
                mainView = null;
            }
            window.setTitleBarVisible(false);
            window.setMaximizable(false);
            window.setContent(new LoginView(this, prefillAccount, serverUrl));
            // 登录页最小尺寸只在这里定一次；要和登录卡的完整高度一致，否则底部按钮会被裁
            window.setMinSize(340, 570);
            window.resizeTo(360, 540);
            window.setTitle("Quack Chat");
        });
    }

    /** 关闭连接但不触发自动重连（切换账号/退出登录时用） */
    private void disconnectSilently() {
        credentials = null;
        ws.close();
    }

    // ------------------------------------------------------------------
    // 发送侧
    // ------------------------------------------------------------------

    public void sendMessage(Conversation conversation, String clientMsgId, String content) {
        if (offline) {
            rejectOffline();
            return;
        }
        SendMessageReq req = SendMessageReq.text(clientMsgId, conversation.getPeerId(),
                conversation.getChatType(), content);
        try {
            ws.send(Cmd.MSG_SEND, req);
        } catch (RuntimeException e) {
            log.warn("发送失败: {}", e.getMessage());
            // 气泡不能永远停在“发送中…”，标成发送失败让用户知道
            Platform.runLater(() -> conversation.markFailed(clientMsgId));
        }
    }

    /** 撤回自己 2 分钟内的消息 */
    public CompletableFuture<ChatMessage> recallMessage(String messageId) {
        if (offline) {
            return CompletableFuture.failedFuture(new IllegalStateException("离线模式不能撤回消息"));
        }
        return ws.request(Cmd.MSG_RECALL, new io.quack.common.dto.RecallReq(messageId),
                        Duration.ofSeconds(10))
                .thenApply(envelope -> {
                    if (envelope.code() != 0) {
                        throw new IllegalStateException(envelope.msg() == null ? "撤回失败" : envelope.msg());
                    }
                    ChatMessage recalled = envelope.dataAs(ChatMessage.class);
                    if (recalled != null) {
                        Platform.runLater(() -> {
                            store.applyRecall(recalled);
                            if (mainView != null) {
                                mainView.refreshConversations();
                            }
                        });
                    }
                    return recalled;
                });
    }

    /** 编辑自己发的文本消息 */
    public CompletableFuture<ChatMessage> editMessage(String messageId, String content) {
        if (offline) {
            return CompletableFuture.failedFuture(new IllegalStateException("离线模式不能编辑消息"));
        }
        return ws.request(Cmd.MSG_EDIT, new io.quack.common.dto.EditReq(messageId, content),
                        Duration.ofSeconds(10))
                .thenApply(envelope -> {
                    if (envelope.code() != 0) {
                        throw new IllegalStateException(envelope.msg() == null ? "编辑失败" : envelope.msg());
                    }
                    ChatMessage edited = envelope.dataAs(ChatMessage.class);
                    if (edited != null) {
                        Platform.runLater(() -> {
                            store.applyEdit(edited);
                            if (mainView != null) {
                                mainView.refreshConversations();
                            }
                        });
                    }
                    return edited;
                });
    }

    public void sendTyping(Conversation conversation) {
        if (offline || !ws.isOpen()) {
            return;
        }
        ws.send(Cmd.TYPING, new TypingNotify(null, null, conversation.getPeerId(), conversation.getChatType()));
    }

    /**
     * 增量同步一个会话：从本地已有的最大 seq 往后补。
     *
     * <p>首次打开（lastSeq=0）等价于“拉最近 50 条历史”；断线重连后则是补缺口。
     */
    public CompletableFuture<io.quack.common.dto.SyncResp> requestSync(Conversation conversation) {
        if (offline) {
            return CompletableFuture.failedFuture(new IllegalStateException("离线模式无法同步"));
        }
        if (!ws.isOpen()) {
            return CompletableFuture.failedFuture(new IllegalStateException("尚未连接服务器"));
        }
        io.quack.common.dto.SyncReq req = new io.quack.common.dto.SyncReq(
                conversation.getPeerId(), conversation.getChatType(), conversation.lastSeq(), 50);
        return ws.request(Cmd.SYNC_REQ, req, Duration.ofSeconds(10)).thenApply(envelope -> {
            if (envelope.code() != 0) {
                throw new IllegalStateException(envelope.msg() == null ? "同步消息失败" : envelope.msg());
            }
            io.quack.common.dto.SyncResp resp = envelope.dataAs(io.quack.common.dto.SyncResp.class);
            if (resp != null) {
                Platform.runLater(() -> {
                    if (mainView != null) {
                        mainView.onSync(conversation, resp);
                    } else {
                        store.applySync(conversation, resp.messages());
                    }
                });
            }
            return resp;
        });
    }

    // ------------------------------------------------------------------
    // 好友：搜索 / 申请 / 审批
    // ------------------------------------------------------------------

    public CompletableFuture<List<User>> searchUsers(String keyword) {
        return ws.request(Cmd.FRIEND_SEARCH, new UserSearchReq(keyword), Duration.ofSeconds(10))
                .thenApply(envelope -> {
                    if (envelope.code() != 0) {
                        throw new IllegalStateException(envelope.msg() == null ? "搜索失败" : envelope.msg());
                    }
                    UserSearchResp resp = envelope.dataAs(UserSearchResp.class);
                    return resp == null || resp.users() == null ? List.<User>of() : resp.users();
                });
    }

    public CompletableFuture<FriendRequest> sendFriendRequest(String toId, String message) {
        return sendFriendRequest(toId, message, null);
    }

    public CompletableFuture<FriendRequest> sendFriendRequest(String toId, String message, String remark) {
        return ws.request(Cmd.FRIEND_REQUEST, new FriendRequestReq(toId, message, remark), Duration.ofSeconds(10))
                .thenApply(envelope -> {
                    if (envelope.code() != 0) {
                        throw new IllegalStateException(envelope.msg() == null ? "申请发送失败" : envelope.msg());
                    }
                    FriendRequestResp resp = envelope.dataAs(FriendRequestResp.class);
                    if (resp == null || resp.request() == null) {
                        throw new IllegalStateException("响应缺少申请信息");
                    }
                    Platform.runLater(() -> mainViewRefreshRequests());
                    return resp.request();
                });
    }

    /** 设置好友备注（昵称）：只影响自己这一侧的显示 */
    public CompletableFuture<io.quack.common.model.Friend> setFriendRemark(String friendId, String remark) {
        return ws.request(Cmd.FRIEND_REMARK, new io.quack.common.dto.FriendRemarkReq(friendId, remark),
                        Duration.ofSeconds(10))
                .thenApply(envelope -> {
                    if (envelope.code() != 0) {
                        throw new IllegalStateException(envelope.msg() == null ? "设置失败" : envelope.msg());
                    }
                    io.quack.common.model.Friend friend =
                            envelope.dataAs(io.quack.common.model.Friend.class);
                    Platform.runLater(() -> {
                        Conversation conversation = store.find(
                                io.quack.common.model.ChatType.PRIVATE, friendId);
                        if (conversation != null && friend != null) {
                            conversation.setTitle(friend.displayName());
                            if (mainView != null) {
                                mainView.refreshConversations();
                            }
                        }
                    });
                    return friend;
                });
    }

    public CompletableFuture<FriendRequestsResp> loadFriendRequests() {
        if (!ws.isOpen()) {
            return CompletableFuture.completedFuture(null);
        }
        return ws.request(Cmd.FRIEND_REQUESTS, null, Duration.ofSeconds(10)).thenApply(envelope -> {
            if (envelope.code() != 0) {
                throw new IllegalStateException(envelope.msg() == null ? "获取申请列表失败" : envelope.msg());
            }
            FriendRequestsResp resp = envelope.dataAs(FriendRequestsResp.class);
            if (resp != null) {
                Platform.runLater(() -> {
                    store.setRequests(resp);
                    mainViewRefreshRequests();
                });
            }
            return resp;
        });
    }

    public CompletableFuture<FriendHandleResp> handleFriendRequest(String requestId, boolean accept) {
        return ws.request(Cmd.FRIEND_HANDLE, new FriendHandleReq(requestId, accept), Duration.ofSeconds(10))
                .thenApply(envelope -> {
                    if (envelope.code() != 0) {
                        throw new IllegalStateException(envelope.msg() == null ? "处理失败" : envelope.msg());
                    }
                    FriendHandleResp resp = envelope.dataAs(FriendHandleResp.class);
                    Platform.runLater(() -> {
                        store.removeIncomingRequest(requestId);
                        if (resp != null && resp.newFriend() != null) {
                            store.addFriend(resp.newFriend());
                            if (mainView != null) {
                                mainView.onFriendAdded(resp.newFriend());
                            }
                        }
                        mainViewRefreshRequests();
                    });
                    return resp;
                });
    }

    private void mainViewRefreshRequests() {
        if (mainView != null) {
            mainView.onRequestsChanged();
        }
    }

    // ------------------------------------------------------------------
    // 资料页 / 群主页
    // ------------------------------------------------------------------

    public CompletableFuture<UserProfile> loadProfile(String qcId) {
        return ws.request(Cmd.PROFILE, new ProfileReq(qcId), Duration.ofSeconds(10))
                .thenApply(envelope -> {
                    if (envelope.code() != 0) {
                        throw new IllegalStateException(envelope.msg() == null ? "加载资料失败" : envelope.msg());
                    }
                    ProfileResp resp = envelope.dataAs(ProfileResp.class);
                    if (resp == null || resp.profile() == null) {
                        throw new IllegalStateException("响应缺少资料");
                    }
                    return resp.profile();
                });
    }

    public CompletableFuture<UserProfile> updateProfile(UpdateProfileReq req) {
        return ws.request(Cmd.PROFILE_UPDATE, req, Duration.ofSeconds(10))
                .thenApply(envelope -> {
                    if (envelope.code() != 0) {
                        throw new IllegalStateException(envelope.msg() == null ? "保存失败" : envelope.msg());
                    }
                    ProfileResp resp = envelope.dataAs(ProfileResp.class);
                    if (resp == null || resp.profile() == null) {
                        throw new IllegalStateException("响应缺少资料");
                    }
                    Platform.runLater(() -> {
                        // 用户名 / 昵称 / 头像都要同步到本地身份，左下角头像才会变
                        store.applyProfile(resp.profile());
                        if (mainView != null) {
                            mainView.refreshMe();
                        }
                    });
                    return resp.profile();
                });
    }

    public CompletableFuture<Integer> likeUser(String qcId) {
        return ws.request(Cmd.PROFILE_LIKE, new LikeReq(qcId), Duration.ofSeconds(10))
                .thenApply(envelope -> {
                    if (envelope.code() != 0) {
                        throw new IllegalStateException(envelope.msg() == null ? "点赞失败" : envelope.msg());
                    }
                    LikeResp resp = envelope.dataAs(LikeResp.class);
                    return resp == null ? 0 : resp.likes();
                });
    }

    public CompletableFuture<GroupDetail> loadGroupDetail(String groupId) {
        return ws.request(Cmd.GROUP_DETAIL, new GroupDetailReq(groupId), Duration.ofSeconds(10))
                .thenApply(envelope -> {
                    if (envelope.code() != 0) {
                        throw new IllegalStateException(envelope.msg() == null ? "加载群资料失败" : envelope.msg());
                    }
                    GroupDetailResp resp = envelope.dataAs(GroupDetailResp.class);
                    if (resp == null || resp.detail() == null) {
                        throw new IllegalStateException("响应缺少群资料");
                    }
                    // 群主自己改完也要刷新列表/头部，不能只等推送
                    Platform.runLater(() -> {
                        Conversation conversation = store.find(ChatType.GROUP, resp.detail().id());
                        if (conversation != null) {
                            conversation.setTitle(resp.detail().name());
                            conversation.setAvatar(resp.detail().avatar());
                        }
                        if (mainView != null) {
                            mainView.refreshConversations();
                        }
                    });
                    return resp.detail();
                });
    }

    public CompletableFuture<GroupDetail> updateGroup(String groupId, String name, String announcement) {
        return updateGroup(groupId, name, announcement, null);
    }

    public CompletableFuture<GroupDetail> updateGroup(String groupId, String name, String announcement,
                                                      String avatar) {
        return ws.request(Cmd.GROUP_UPDATE, new GroupUpdateReq(groupId, name, announcement, avatar),
                        Duration.ofSeconds(10))
                .thenApply(envelope -> {
                    if (envelope.code() != 0) {
                        throw new IllegalStateException(envelope.msg() == null ? "保存失败" : envelope.msg());
                    }
                    GroupDetailResp resp = envelope.dataAs(GroupDetailResp.class);
                    if (resp == null || resp.detail() == null) {
                        throw new IllegalStateException("响应缺少群资料");
                    }
                    // 群主自己改完也要刷新列表/头部，不能只等推送
                    Platform.runLater(() -> {
                        Conversation conversation = store.find(ChatType.GROUP, resp.detail().id());
                        if (conversation != null) {
                            conversation.setTitle(resp.detail().name());
                            conversation.setAvatar(resp.detail().avatar());
                        }
                        if (mainView != null) {
                            mainView.refreshConversations();
                        }
                    });
                    Platform.runLater(() -> {
                        store.conversations().stream()
                                .filter(c -> c.getChatType() == ChatType.GROUP && c.getPeerId().equals(groupId))
                                .findFirst()
                                .ifPresent(c -> c.setTitle(resp.detail().name()));
                        if (mainView != null) {
                            mainView.onGroupUpdated(resp.detail());
                        }
                    });
                    return resp.detail();
                });
    }

    /** 创建群聊：我是群主，把选中的好友拉进来 */
    public CompletableFuture<GroupDetail> createGroup(String name, java.util.List<String> memberIds) {
        return ws.request(Cmd.GROUP_CREATE, new io.quack.common.dto.GroupCreateReq(name, memberIds),
                        Duration.ofSeconds(10))
                .thenApply(envelope -> {
                    if (envelope.code() != 0) {
                        throw new IllegalStateException(envelope.msg() == null ? "创建群聊失败" : envelope.msg());
                    }
                    io.quack.common.dto.GroupDetailResp resp =
                            envelope.dataAs(io.quack.common.dto.GroupDetailResp.class);
                    if (resp == null || resp.detail() == null) {
                        throw new IllegalStateException("创建群聊响应缺少群资料");
                    }
                    GroupDetail detail = resp.detail();
                    Platform.runLater(() -> {
                        store.ensureGroupConversation(detail);
                        if (mainView != null) {
                            mainView.refreshConversations();
                            mainView.onGroupUpdated(detail);
                        }
                    });
                    return detail;
                });
    }
    public CompletableFuture<Void> quitGroup(String groupId) {
        return ws.request(Cmd.GROUP_QUIT, new GroupQuitReq(groupId), Duration.ofSeconds(10))
                .thenApply(envelope -> {
                    if (envelope.code() != 0) {
                        throw new IllegalStateException(envelope.msg() == null ? "退群失败" : envelope.msg());
                    }
                    Platform.runLater(() -> {
                        store.conversations().stream()
                                .filter(c -> c.getChatType() == ChatType.GROUP && c.getPeerId().equals(groupId))
                                .findFirst()
                                .ifPresent(store::removeConversation);
                        if (mainView != null) {
                            mainView.onGroupLeft(groupId);
                        }
                    });
                    return null;
                });
    }

    // ------------------------------------------------------------------
    // 附件 / 礼物
    // ------------------------------------------------------------------

    /** 上传并发送图片或文件：先用 HTTP 传文件，再把 URL 作为消息内容发出去 */
    public void sendAttachment(Conversation conversation, Path file, boolean image) {
        if (offline) {
            rejectOffline();
            return;
        }
        if (conversation == null || file == null) {
            return;
        }
        uploadFile(file).whenComplete((uploaded, error) -> {
            if (error != null) {
                Platform.runLater(() -> {
                    if (mainView != null) {
                        mainView.showBanner("发送失败：" + ClientErrors.describe(error), true);
                    }
                });
                return;
            }
            String content = image
                    ? uploaded.url()
                    // 文件：URL|名字|大小|发送方本地路径（发送方点开直接打开本地文件）
                    : uploaded.url() + "|" + uploaded.name() + "|" + uploaded.size()
                            + "|" + file.toAbsolutePath();
            sendWithType(conversation, image ? MsgType.IMAGE : MsgType.FILE, content);
        });
    }

    /** 礼物：content = giftId|名称|图标 */
    public void sendGift(Conversation conversation, String giftId, String name, String icon) {
        sendWithType(conversation, MsgType.GIFT, giftId + "|" + name + "|" + icon);
    }

    /** 上报已读（打开会话或收到新消息时调用） */
    public void markRead(Conversation conversation) {
        if (conversation == null || me == null || offline || !ws.isOpen()) {
            return;
        }
        if (conversation.messages().isEmpty()) {
            // 空会话不要上报：否则会把“已读位点”推到当前时间/0，语义就错了
            return;
        }
        long ts = conversation.messages().isEmpty()
                ? System.currentTimeMillis()
                : conversation.messages().get(conversation.messages().size() - 1).ts();
        long seq = conversation.lastSeq();
        if (seq <= 0 && !conversation.messages().isEmpty()) {
            seq = conversation.messages().get(conversation.messages().size() - 1).seq();
        }
        ws.send(Cmd.MSG_READ, new io.quack.common.dto.ReadReq(
                conversation.getPeerId(), conversation.getChatType().name(), ts, seq));
    }

    private void sendWithType(Conversation conversation, MsgType type, String content) {
        if (offline) {
            rejectOffline();
            return;
        }
        User me = this.me;
        if (me == null) {
            return;
        }
        if (!ws.isOpen()) {
            if (mainView != null) {
                mainView.showBanner("连接已断开，文件未发送", false);
            }
            return;
        }
        String clientMsgId = UUID.randomUUID().toString();
        ChatMessage local = new ChatMessage(
                Conversation.LOCAL_ID_PREFIX + clientMsgId, clientMsgId,
                me.id(), me.nickname(), conversation.getPeerId(), conversation.getChatType(),
                type, content, System.currentTimeMillis());

        Platform.runLater(() -> {
            conversation.messages().add(local);
            if (mainView != null) {
                mainView.onMessageArrived(conversation);
            }
        });
        try {
            ws.send(Cmd.MSG_SEND, new SendMessageReq(clientMsgId, conversation.getPeerId(),
                    conversation.getChatType(), type, content));
        } catch (RuntimeException error) {
            log.warn("发送附件失败: {}", error.getMessage());
            Platform.runLater(() -> conversation.markFailed(clientMsgId));
        }
    }

    /** 视频播放交给系统：下载后调用系统播放器（应用内只保留封面缩略图） */
    public void playVideo(String url) {
        String resolved = io.quack.client.net.MediaUrls.resolve(url);
        if (mainView != null) {
            mainView.showBanner("正在下载视频…", false);
        }
        io.quack.client.media.SystemVideo.open(resolved, message -> Platform.runLater(() -> {
            if (mainView != null) {
                boolean error = message.startsWith("视频打开失败");
                mainView.showBanner(message, error);
            }
        }));
    }

    // ------------------------------------------------------------------
    // QC 空间
    // ------------------------------------------------------------------

    /** 某个人的空间（qcId 非空）；qcId 为空表示好友动态流 */
    public CompletableFuture<MomentListResp> loadMoments(String qcId, long beforeTs, int limit) {
        return ws.request(Cmd.MOMENT_LIST, new io.quack.common.dto.MomentListReq(qcId, beforeTs, limit),
                        Duration.ofSeconds(10))
                .thenApply(envelope -> {
                    if (envelope.code() != 0) {
                        throw new IllegalStateException(envelope.msg() == null ? "加载失败" : envelope.msg());
                    }
                    MomentListResp resp = envelope.dataAs(MomentListResp.class);
                    return resp == null
                            ? new MomentListResp(List.of(), 0, false) : resp;
                });
    }

    public CompletableFuture<Moment> publishMoment(String text) {
        return publishMoment(text, List.of());
    }

    public CompletableFuture<Moment> publishMoment(String text, List<MomentMedia> media) {
        return publishMoment(text, media, "PUBLIC");
    }

    public CompletableFuture<Moment> publishMoment(String text, List<MomentMedia> media, String visibility) {
        if (offline) {
            return CompletableFuture.failedFuture(new IllegalStateException("离线模式不能发动态"));
        }
        return ws.request(Cmd.MOMENT_PUBLISH, new MomentPublishReq(text, media, visibility),
                        Duration.ofSeconds(15))
                .thenApply(envelope -> {
                    if (envelope.code() != 0) {
                        throw new IllegalStateException(envelope.msg() == null ? "发布失败" : envelope.msg());
                    }
                    MomentResp resp = envelope.dataAs(MomentResp.class);
                    if (resp == null || resp.moment() == null) {
                        throw new IllegalStateException("响应缺少说说");
                    }
                    return resp.moment();
                });
    }

    public CompletableFuture<Moment> editMoment(String momentId, String text, String visibility) {
        return ws.request(Cmd.MOMENT_EDIT,
                        new io.quack.common.dto.MomentEditReq(momentId, text, visibility),
                        Duration.ofSeconds(10))
                .thenApply(envelope -> {
                    if (envelope.code() != 0) {
                        throw new IllegalStateException(envelope.msg() == null ? "编辑失败" : envelope.msg());
                    }
                    MomentResp resp = envelope.dataAs(MomentResp.class);
                    return resp == null ? null : resp.moment();
                });
    }

    public CompletableFuture<Moment> shareMoment(String sourceId, String text, String visibility) {
        return ws.request(Cmd.MOMENT_SHARE,
                        new io.quack.common.dto.MomentShareReq(sourceId, text, visibility),
                        Duration.ofSeconds(10))
                .thenApply(envelope -> {
                    if (envelope.code() != 0) {
                        throw new IllegalStateException(envelope.msg() == null ? "转发失败" : envelope.msg());
                    }
                    MomentResp resp = envelope.dataAs(MomentResp.class);
                    return resp == null ? null : resp.moment();
                });
    }

    public CompletableFuture<List<User>> loadMomentLikes(String momentId) {
        return ws.request(Cmd.MOMENT_LIKES, new MomentLikeReq(momentId, false),
                        Duration.ofSeconds(10))
                .thenApply(envelope -> {
                    if (envelope.code() != 0) {
                        throw new IllegalStateException(envelope.msg() == null ? "加载失败" : envelope.msg());
                    }
                    io.quack.common.dto.MomentLikesResp resp =
                            envelope.dataAs(io.quack.common.dto.MomentLikesResp.class);
                    return resp == null || resp.users() == null ? List.<User>of() : resp.users();
                });
    }

    public CompletableFuture<io.quack.common.dto.MomentMediaResp> loadMomentMedia(
            String qcId, long beforeTs, int limit) {
        return ws.request(Cmd.MOMENT_MEDIA, new io.quack.common.dto.MomentListReq(qcId, beforeTs, limit),
                        Duration.ofSeconds(10))
                .thenApply(envelope -> {
                    if (envelope.code() != 0) {
                        throw new IllegalStateException(envelope.msg() == null ? "加载相册失败" : envelope.msg());
                    }
                    return envelope.dataAs(io.quack.common.dto.MomentMediaResp.class);
                });
    }

    public CompletableFuture<io.quack.common.dto.MomentStatsResp> loadMomentStats(String qcId) {
        return ws.request(Cmd.MOMENT_STATS, new io.quack.common.dto.MomentListReq(qcId, 0, 1),
                        Duration.ofSeconds(10))
                .thenApply(envelope -> {
                    if (envelope.code() != 0) {
                        throw new IllegalStateException(envelope.msg() == null ? "加载统计失败" : envelope.msg());
                    }
                    return envelope.dataAs(io.quack.common.dto.MomentStatsResp.class);
                });
    }

    public CompletableFuture<Moment> likeMoment(String id) {
        return likeMoment(id, true);
    }

    public CompletableFuture<Moment> likeMoment(String id, boolean liked) {
        return ws.request(Cmd.MOMENT_LIKE, new MomentLikeReq(id, liked), Duration.ofSeconds(10))
                .thenApply(envelope -> {
                    if (envelope.code() != 0) {
                        throw new IllegalStateException(envelope.msg() == null ? "操作失败" : envelope.msg());
                    }
                    MomentResp resp = envelope.dataAs(MomentResp.class);
                    return resp == null ? null : resp.moment();
                });
    }

    public CompletableFuture<MomentComment> addMomentComment(String momentId, String text) {
        return addMomentComment(momentId, text, null);
    }

    public CompletableFuture<MomentComment> addMomentComment(String momentId, String text, String parentId) {
        return ws.request(Cmd.MOMENT_COMMENT_ADD,
                        new io.quack.common.dto.MomentCommentReq(momentId, null, text, parentId),
                        Duration.ofSeconds(10))
                .thenApply(envelope -> {
                    if (envelope.code() != 0) {
                        throw new IllegalStateException(envelope.msg() == null ? "评论失败" : envelope.msg());
                    }
                    io.quack.common.dto.MomentCommentsResp resp =
                            envelope.dataAs(io.quack.common.dto.MomentCommentsResp.class);
                    return resp == null || resp.comments() == null || resp.comments().isEmpty()
                            ? null : resp.comments().get(0);
                });
    }

    public CompletableFuture<List<MomentComment>> loadMomentComments(String momentId) {
        return ws.request(Cmd.MOMENT_COMMENT_LIST,
                        new io.quack.common.dto.MomentCommentReq(momentId, null, null), Duration.ofSeconds(10))
                .thenApply(envelope -> {
                    if (envelope.code() != 0) {
                        throw new IllegalStateException(envelope.msg() == null ? "加载评论失败" : envelope.msg());
                    }
                    io.quack.common.dto.MomentCommentsResp resp =
                            envelope.dataAs(io.quack.common.dto.MomentCommentsResp.class);
                    return resp == null || resp.comments() == null ? List.<MomentComment>of() : resp.comments();
                });
    }

    public CompletableFuture<Void> deleteMomentComment(String commentId) {
        return ws.request(Cmd.MOMENT_COMMENT_DELETE,
                        new io.quack.common.dto.MomentCommentReq(null, commentId, null), Duration.ofSeconds(10))
                .thenApply(envelope -> {
                    if (envelope.code() != 0) {
                        throw new IllegalStateException(envelope.msg() == null ? "删除评论失败" : envelope.msg());
                    }
                    return null;
                });
    }

    public CompletableFuture<Void> deleteMoment(String id) {
        return ws.request(Cmd.MOMENT_DELETE,
                        new io.quack.common.dto.MomentCommentReq(id, null, null), Duration.ofSeconds(10))
                .thenApply(envelope -> {
                    if (envelope.code() != 0) {
                        throw new IllegalStateException(envelope.msg() == null ? "删除失败" : envelope.msg());
                    }
                    return null;
                });
    }

    // ------------------------------------------------------------------
    // 图片 / 视频上传（聊天与空间共用）
    // ------------------------------------------------------------------

    public CompletableFuture<io.quack.common.model.MomentMedia> uploadMomentImage(Path file) {
        return uploadFile(file).thenApply(uploaded -> {
            int width = 0;
            int height = 0;
            try {
                var image = javax.imageio.ImageIO.read(file.toFile());
                if (image != null) {
                    width = image.getWidth();
                    height = image.getHeight();
                }
            } catch (Exception ignored) {
                // 尺寸只用于布局，拿不到就按 0 处理
            }
            return io.quack.common.model.MomentMedia.image(uploaded.url(), width, height);
        });
    }

    public CompletableFuture<io.quack.common.model.MomentMedia> uploadMomentVideo(Path file) {
        return io.quack.client.media.VideoThumbnailer.capture(file)
                .thenCompose(thumb -> {
                    CompletableFuture<FileUploader.Uploaded> videoFuture = uploadFile(file);
                    CompletableFuture<FileUploader.Uploaded> coverFuture = thumb.file() == null
                            ? CompletableFuture.completedFuture(null)
                            : uploadFile(thumb.file());
                    return videoFuture.thenCombine(coverFuture, (video, cover) -> {
                        io.quack.client.media.VideoThumbnailer.deleteQuietly(thumb.file());
                        String coverUrl = cover == null ? video.url() : cover.url();
                        return io.quack.common.model.MomentMedia.video(video.url(), coverUrl,
                                thumb.durationMs(), thumb.width(), thumb.height());
                    });
                });
    }

    /** 聊天里发送视频：先在本机抽一帧当封面，再上传视频 + 封面 */
    public void sendVideo(Conversation conversation, Path file) {
        if (offline) {
            rejectOffline();
            return;
        }
        if (conversation == null || file == null) {
            return;
        }
        if (mainView != null) {
            mainView.showBanner("正在上传视频…", false);
        }
        uploadMomentVideo(file).whenComplete((media, error) -> {
            if (error != null) {
                Platform.runLater(() -> {
                    if (mainView != null) {
                        mainView.showBanner("视频发送失败：" + ClientErrors.describe(error), true);
                    }
                });
                return;
            }
            String content = media.url() + "|" + media.cover() + "|" + media.durationMs()
                    + "|" + media.width() + "|" + media.height() + "|" + file.getFileName();
            Platform.runLater(() -> sendWithType(conversation, MsgType.VIDEO, content));
        });
    }

    // ------------------------------------------------------------------
    // 群管理 / 精华消息
    // ------------------------------------------------------------------

    public CompletableFuture<GroupDetail> inviteMembers(String groupId, List<String> userIds) {
        return groupOp(Cmd.GROUP_INVITE, new GroupInviteReq(groupId, userIds));
    }

    public CompletableFuture<GroupDetail> kickMember(String groupId, String userId) {
        return groupOp(Cmd.GROUP_KICK, new GroupMemberOpReq(groupId, userId, null));
    }

    public CompletableFuture<GroupDetail> transferOwner(String groupId, String newOwnerId) {
        return groupOp(Cmd.GROUP_TRANSFER, new GroupMemberOpReq(groupId, newOwnerId, null));
    }

    /** 设置“我在本群的昵称”，空串清除 */
    public CompletableFuture<GroupDetail> setGroupAlias(String groupId, String alias) {
        return groupOp(Cmd.GROUP_ALIAS, new io.quack.common.dto.GroupAliasReq(groupId, alias));
    }

    /** 群主发布一条新公告 */
    public CompletableFuture<GroupDetail> addGroupAnnouncement(String groupId, String text) {
        return groupOp(Cmd.GROUP_ANNOUNCEMENT_ADD,
                new io.quack.common.dto.GroupAnnouncementReq(groupId, text));
    }

    /** 按群号/群名搜索群 */
    public CompletableFuture<List<io.quack.common.model.ChatGroup>> searchGroups(String keyword) {
        return ws.request(Cmd.GROUP_SEARCH, new io.quack.common.dto.GroupSearchReq(keyword),
                        Duration.ofSeconds(10))
                .thenApply(envelope -> {
                    if (envelope.code() != 0) {
                        throw new IllegalStateException(envelope.msg() == null ? "搜索群失败" : envelope.msg());
                    }
                    io.quack.common.dto.GroupSearchResp resp =
                            envelope.dataAs(io.quack.common.dto.GroupSearchResp.class);
                    return resp == null || resp.groups() == null
                            ? List.<io.quack.common.model.ChatGroup>of() : resp.groups();
                });
    }

    /** 加入群聊：提交申请，等待群主/管理员审批 */
    public CompletableFuture<io.quack.common.model.GroupJoinRequest> requestJoinGroup(String groupId) {
        return ws.request(Cmd.GROUP_JOIN, new io.quack.common.dto.GroupJoinReq(groupId),
                        Duration.ofSeconds(10))
                .thenApply(envelope -> {
                    if (envelope.code() != 0) {
                        throw new IllegalStateException(envelope.msg() == null ? "申请入群失败" : envelope.msg());
                    }
                    return envelope.dataAs(io.quack.common.model.GroupJoinRequest.class);
                })
                .thenApply(request -> {
                    // 申请发出后「待处理」角标要立刻出现
                    refreshGroupJoinBadge();
                    return request;
                });
    }

    /** 待处理 + 我发出的入群申请 */
    public CompletableFuture<List<io.quack.common.model.GroupJoinRequest>> loadGroupJoinRequests() {
        return ws.request(Cmd.GROUP_JOIN_REQUESTS, null, Duration.ofSeconds(10))
                .thenApply(envelope -> {
                    if (envelope.code() != 0) {
                        throw new IllegalStateException(envelope.msg() == null ? "加载入群申请失败" : envelope.msg());
                    }
                    io.quack.common.dto.GroupJoinRequestsResp resp =
                            envelope.dataAs(io.quack.common.dto.GroupJoinRequestsResp.class);
                    return resp == null || resp.requests() == null
                            ? List.<io.quack.common.model.GroupJoinRequest>of() : resp.requests();
                });
    }

    /** 同意/拒绝入群申请，并刷新待处理角标 */
    public CompletableFuture<io.quack.common.model.GroupJoinRequest> handleGroupJoinRequest(
            String requestId, boolean accept) {
        return ws.request(Cmd.GROUP_JOIN_HANDLE,
                        new io.quack.common.dto.GroupJoinHandleReq(requestId, accept),
                        Duration.ofSeconds(10))
                .thenApply(envelope -> {
                    if (envelope.code() != 0) {
                        throw new IllegalStateException(envelope.msg() == null ? "处理失败" : envelope.msg());
                    }
                    io.quack.common.dto.GroupJoinRequestsResp resp =
                            envelope.dataAs(io.quack.common.dto.GroupJoinRequestsResp.class);
                    io.quack.common.model.GroupJoinRequest updated =
                            resp == null || resp.requests() == null || resp.requests().isEmpty()
                                    ? null : resp.requests().get(0);
                    refreshGroupJoinBadge();
                    return updated;
                });
    }

    /** 群主任命管理员 */
    public CompletableFuture<GroupDetail> addGroupAdmin(String groupId, String userId) {
        return groupOp(Cmd.GROUP_ADMIN_ADD, new io.quack.common.dto.GroupAdminReq(groupId, userId));
    }

    /** 群主取消管理员 */
    public CompletableFuture<GroupDetail> removeGroupAdmin(String groupId, String userId) {
        return groupOp(Cmd.GROUP_ADMIN_REMOVE, new io.quack.common.dto.GroupAdminReq(groupId, userId));
    }

    /** 群主解散群聊 */
    public CompletableFuture<Void> dissolveGroup(String groupId) {
        return ws.request(Cmd.GROUP_DISSOLVE,
                        new io.quack.common.dto.GroupDissolveReq(groupId), Duration.ofSeconds(10))
                .thenApply(envelope -> {
                    if (envelope.code() != 0) {
                        throw new IllegalStateException(envelope.msg() == null ? "解散群失败" : envelope.msg());
                    }
                    Platform.runLater(() -> {
                        io.quack.client.store.Conversation conversation =
                                store.find(ChatType.GROUP, groupId);
                        if (conversation != null) {
                            store.removeConversation(conversation);
                        }
                        if (mainView != null) {
                            mainView.onGroupLeft(groupId);
                        }
                    });
                    return null;
                });
    }

    public javafx.beans.property.IntegerProperty pendingGroupRequestsProperty() {
        return pendingGroupRequests;
    }

    /** 重新拉取入群申请数量，更新「待处理」角标 */
    public void refreshGroupJoinBadge() {
        if (offline || !ws.isOpen()) {
            return;
        }
        loadGroupJoinRequests().thenAccept(list -> Platform.runLater(() ->
                pendingGroupRequests.set((int) list.stream()
                        .filter(io.quack.common.model.GroupJoinRequest::pending).count())))
                .exceptionally(error -> null);
    }

    public CompletableFuture<List<ChatMessage>> loadEssence(String groupId) {
        return ws.request(Cmd.GROUP_ESSENCE_LIST, new GroupMemberOpReq(groupId, null, null),
                        Duration.ofSeconds(10))
                .thenApply(envelope -> {
                    if (envelope.code() != 0) {
                        throw new IllegalStateException(envelope.msg() == null ? "加载精华失败" : envelope.msg());
                    }
                    EssenceListResp resp = envelope.dataAs(EssenceListResp.class);
                    return resp == null || resp.messages() == null ? List.<ChatMessage>of() : resp.messages();
                });
    }

    public CompletableFuture<List<ChatMessage>> addEssence(String groupId, String messageId) {
        return ws.request(Cmd.GROUP_ESSENCE_ADD, new GroupMemberOpReq(groupId, null, messageId),
                        Duration.ofSeconds(10))
                .thenApply(envelope -> {
                    if (envelope.code() != 0) {
                        throw new IllegalStateException(envelope.msg() == null ? "操作失败" : envelope.msg());
                    }
                    EssenceListResp resp = envelope.dataAs(EssenceListResp.class);
                    return resp == null || resp.messages() == null ? List.<ChatMessage>of() : resp.messages();
                });
    }

    public CompletableFuture<List<ChatMessage>> removeEssence(String groupId, String messageId) {
        return ws.request(Cmd.GROUP_ESSENCE_REMOVE, new GroupMemberOpReq(groupId, null, messageId),
                        Duration.ofSeconds(10))
                .thenApply(envelope -> {
                    if (envelope.code() != 0) {
                        throw new IllegalStateException(envelope.msg() == null ? "操作失败" : envelope.msg());
                    }
                    EssenceListResp resp = envelope.dataAs(EssenceListResp.class);
                    return resp == null || resp.messages() == null ? List.<ChatMessage>of() : resp.messages();
                });
    }

    private CompletableFuture<GroupDetail> groupOp(String cmd, Object payload) {
        return ws.request(cmd, payload, Duration.ofSeconds(10))
                .thenApply(envelope -> {
                    if (envelope.code() != 0) {
                        throw new IllegalStateException(envelope.msg() == null ? "操作失败" : envelope.msg());
                    }
                    GroupDetailResp resp = envelope.dataAs(GroupDetailResp.class);
                    if (resp == null || resp.detail() == null) {
                        throw new IllegalStateException("响应缺少群资料");
                    }
                    // 群主自己改完也要刷新列表/头部，不能只等推送
                    Platform.runLater(() -> {
                        Conversation conversation = store.find(ChatType.GROUP, resp.detail().id());
                        if (conversation != null) {
                            conversation.setTitle(resp.detail().name());
                            conversation.setAvatar(resp.detail().avatar());
                        }
                        if (mainView != null) {
                            mainView.refreshConversations();
                        }
                    });
                    Platform.runLater(() -> {
                        if (mainView != null) {
                            mainView.onGroupUpdated(resp.detail());
                        }
                    });
                    return resp.detail();
                });
    }

    // ------------------------------------------------------------------
    // 通话信令
    // ------------------------------------------------------------------

    /** 群通话：toId 传群号（q10001），服务端会通知所有在线成员 */
    public CompletableFuture<CallSignal> startGroupCall(String groupId) {
        return startCall(groupId);
    }

    public CompletableFuture<CallSignal> startCall(String toId) {
        return ws.request(Cmd.CALL_INVITE, new CallReq(null, toId), Duration.ofSeconds(10))
                .thenApply(this::callSignalOf);
    }

    public void answerCall(String callId) {
        ws.send(Cmd.CALL_ACCEPT, new CallReq(callId, null));
    }

    public void rejectCall(String callId) {
        ws.send(Cmd.CALL_REJECT, new CallReq(callId, null));
    }

    public void hangupCall(String callId) {
        ws.send(Cmd.CALL_HANGUP, new CallReq(callId, null));
    }

    // ------------------------------------------------------------------
    // 通话音频（麦克风 ↔ WebSocket ↔ 扬声器）
    // ------------------------------------------------------------------

    /** @return null 表示音频已开始，否则是错误说明（通话仍可继续，只是没声音） */
    public String beginAudio(String callId) {
        endAudio();
        audioCallId = callId;
        audioCall = new AudioCall(frame -> outboundAudio.add(Base64.getEncoder().encodeToString(frame)));
        String error = audioCall.start();
        if (error != null) {
            audioCall = null;
            return error;
        }
        outboundAudio.clear();
        // 每 20ms 发一帧，最多补发 3 帧，避免网络抖动时越积越多
        audioSender = new javafx.animation.Timeline(
                new javafx.animation.KeyFrame(javafx.util.Duration.millis(20), event -> {
                    for (int i = 0; i < 3; i++) {
                        String chunk = outboundAudio.poll();
                        if (chunk == null) {
                            break;
                        }
                        ws.send(Cmd.CALL_AUDIO, new CallAudioReq(audioCallId, chunk));
                    }
                }));
        audioSender.setCycleCount(javafx.animation.Animation.INDEFINITE);
        audioSender.play();
        return null;
    }

    public void endAudio() {
        if (audioSender != null) {
            audioSender.stop();
            audioSender = null;
        }
        outboundAudio.clear();
        if (audioCall != null) {
            audioCall.close();
            audioCall = null;
        }
        audioCallId = null;
    }

    /** @return 切换后是否处于静音 */
    public boolean toggleMute() {
        if (audioCall == null) {
            return false;
        }
        boolean next = !audioCall.isMuted();
        audioCall.setMuted(next);
        return next;
    }

    public boolean isAudioActive() {
        return audioCall != null;
    }

    private CallSignal callSignalOf(Envelope envelope) {
        if (envelope.code() != 0) {
            throw new IllegalStateException(envelope.msg() == null ? "通话失败" : envelope.msg());
        }
        CallSignal signal = envelope.dataAs(CallSignal.class);
        if (signal == null) {
            throw new IllegalStateException("响应缺少通话信息");
        }
        return signal;
    }

    // ------------------------------------------------------------------
    // 接收侧
    // ------------------------------------------------------------------

    private void handleEnvelope(Envelope envelope) {
        switch (envelope.cmd()) {
            case Cmd.MSG_PUSH -> {
                ChatMessage message = envelope.dataAs(ChatMessage.class);
                if (message != null) {
                    enqueuePush(message);
                }
            }
            case Cmd.MSG_ACK -> {
                SendMessageAck ack = envelope.dataAs(SendMessageAck.class);
                if (ack != null) {
                    Platform.runLater(() -> store.markDelivered(ack));
                }
            }
            case Cmd.MSG_RECALL_PUSH -> {
                ChatMessage recalled = envelope.dataAs(ChatMessage.class);
                if (recalled != null) {
                    Platform.runLater(() -> {
                        store.applyRecall(recalled);
                        if (mainView != null) {
                            mainView.refreshConversations();
                        }
                    });
                }
            }
            case Cmd.MSG_EDIT_PUSH -> {
                ChatMessage edited = envelope.dataAs(ChatMessage.class);
                if (edited != null) {
                    Platform.runLater(() -> {
                        store.applyEdit(edited);
                        if (mainView != null) {
                            mainView.refreshConversations();
                        }
                    });
                }
            }
            case Cmd.MOMENT_LIKED_PUSH -> {
                Moment moment = envelope.dataAs(Moment.class);
                if (moment != null) {
                    Platform.runLater(() -> {
                        if (mainView != null) {
                            mainView.showBanner("有人赞了你的空间动态", false);
                        }
                    });
                }
            }
            case Cmd.MOMENT_COMMENT_PUSH -> {
                MomentComment comment = envelope.dataAs(MomentComment.class);
                if (comment != null) {
                    Platform.runLater(() -> {
                        if (mainView != null) {
                            String text = comment.text() == null ? "" : comment.text();
                            if (text.length() > 20) {
                                text = text.substring(0, 20) + "…";
                            }
                            mainView.showBanner(comment.authorName() + " 评论了你的动态：" + text, false);
                        }
                    });
                }
            }
            case Cmd.MOMENT_EDITED_PUSH -> {
                Moment edited = envelope.dataAs(Moment.class);
                if (edited != null) {
                    Platform.runLater(() -> {
                        if (mainView != null) {
                            mainView.showBanner("一条空间动态已编辑", false);
                        }
                    });
                }
            }
            case Cmd.MOMENT_SHARED_PUSH -> {
                Moment shared = envelope.dataAs(Moment.class);
                if (shared != null) {
                    Platform.runLater(() -> {
                        if (mainView != null) {
                            mainView.showBanner("有人转发了你的空间动态", false);
                        }
                    });
                }
            }
            case Cmd.UNREAD_PUSH -> {
                io.quack.common.dto.UnreadPush unread =
                        envelope.dataAs(io.quack.common.dto.UnreadPush.class);
                if (unread != null) {
                    Platform.runLater(() -> {
                        store.setUnread(unread.convKey(), unread.unread());
                        if (mainView != null) {
                            mainView.refreshConversations();
                        }
                    });
                }
            }
            case Cmd.PRESENCE -> {
                PresenceNotify notify = envelope.dataAs(PresenceNotify.class);
                if (notify == null) {
                    return;
                }
                Platform.runLater(() -> {
                    store.updatePresence(notify.userId(), notify.online());
                    // 在线点不是可观察属性，ListView 不会自己重绘，必须手动刷
                    if (mainView != null) {
                        mainView.refreshConversations();
                        mainView.refreshContacts();
                    }
                });
            }
            case Cmd.FRIEND_ADDED -> {
                Friend friend = envelope.dataAs(Friend.class);
                if (friend != null) {
                    Platform.runLater(() -> {
                        store.addFriend(friend);
                        if (mainView != null) {
                            mainView.onFriendAdded(friend);
                        }
                    });
                }
            }
            case Cmd.FRIEND_REQUEST_PUSH -> {
                FriendRequest request = envelope.dataAs(FriendRequest.class);
                if (request != null) {
                    Platform.runLater(() -> {
                        store.addIncomingRequest(request);
                        mainViewRefreshRequests();
                        if (mainView != null) {
                            mainView.showBanner("收到新的好友申请，点左侧「新的朋友」查看", false);
                        }
                    });
                }
            }
            case Cmd.FRIEND_HANDLED_PUSH -> {
                FriendRequest request = envelope.dataAs(FriendRequest.class);
                if (request != null) {
                    Platform.runLater(() -> {
                        store.removeOutgoingRequest(request.id());
                        mainViewRefreshRequests();
                        if (mainView != null) {
                            mainView.showBanner("对方拒绝了你的好友申请", false);
                        }
                    });
                }
            }
            case Cmd.TYPING -> {
                TypingNotify notify = envelope.dataAs(TypingNotify.class);
                if (notify != null) {
                    Platform.runLater(() -> {
                        if (mainView != null) {
                            mainView.onTyping(notify);
                        }
                    });
                }
            }
            case Cmd.CALL_INCOMING -> {
                CallSignal signal = envelope.dataAs(CallSignal.class);
                if (signal != null) {
                    Platform.runLater(() -> {
                        if (mainView != null) {
                            mainView.onIncomingCall(signal);
                        }
                    });
                }
            }
            case Cmd.GROUP_UPDATED_PUSH -> {
                io.quack.common.model.GroupDetail detail =
                        envelope.dataAs(io.quack.common.model.GroupDetail.class);
                if (detail != null) {
                    Platform.runLater(() -> {
                        io.quack.client.store.Conversation conversation =
                                store.find(io.quack.common.model.ChatType.GROUP, detail.id());
                        if (conversation != null) {
                            conversation.setTitle(detail.name());
                            conversation.setAvatar(detail.avatar());
                        } else {
                            // 新建群 / 被拉进群：本地还没有会话，要补一条
                            store.ensureGroupConversation(detail);
                        }
                        if (mainView != null) {
                            mainView.refreshConversations();
                            mainView.onGroupUpdated(detail);
                        }
                    });
                }
            }
            case Cmd.GROUP_JOIN_REQUEST_PUSH -> {
                io.quack.common.model.GroupJoinRequest request =
                        envelope.dataAs(io.quack.common.model.GroupJoinRequest.class);
                if (request != null) {
                    Platform.runLater(() -> {
                        if (mainView != null) {
                            mainView.showBanner(request.userName() + " 申请加入「"
                                    + request.groupName() + "」", false);
                        }
                        refreshGroupJoinBadge();
                    });
                }
            }
            case Cmd.GROUP_JOIN_RESULT_PUSH -> {
                io.quack.common.model.GroupJoinRequest request =
                        envelope.dataAs(io.quack.common.model.GroupJoinRequest.class);
                if (request != null) {
                    Platform.runLater(() -> {
                        boolean approved = io.quack.common.model.GroupJoinRequest.APPROVED
                                .equalsIgnoreCase(request.status());
                        if (approved) {
                            loadGroupDetail(request.groupId()).thenAccept(detail -> Platform.runLater(() -> {
                                if (detail != null) {
                                    store.ensureGroupConversation(detail);
                                    if (mainView != null) {
                                        mainView.onGroupUpdated(detail);
                                    }
                                }
                            })).exceptionally(error -> null);
                        }
                        if (mainView != null && me != null && me.id().equals(request.userId())) {
                            mainView.showBanner(approved
                                    ? "入群申请已通过：「" + request.groupName() + "」"
                                    : "入群申请被拒绝：「" + request.groupName() + "」", false);
                        }
                        refreshGroupJoinBadge();
                    });
                }
            }
            case Cmd.GROUP_REMOVED_PUSH -> {
                io.quack.common.dto.GroupRemoved removed =
                        envelope.dataAs(io.quack.common.dto.GroupRemoved.class);
                if (removed != null) {
                    Platform.runLater(() -> {
                        io.quack.client.store.Conversation conversation =
                                store.find(io.quack.common.model.ChatType.GROUP, removed.groupId());
                        if (conversation != null) {
                            store.removeConversation(conversation);
                        }
                        if (mainView != null) {
                            mainView.onGroupLeft(removed.groupId());
                            String reason = removed.reason() == null ? "" : removed.reason();
                            String message = "quit".equalsIgnoreCase(reason)
                                    ? "你已退出群聊"
                                    : "dismissed".equalsIgnoreCase(reason)
                                    ? "群聊已解散" : "你已被移出群聊";
                            mainView.showBanner(message, false);
                        }
                    });
                }
            }
            case Cmd.MSG_READ_PUSH -> {
                io.quack.common.dto.ReadNotify notify =
                        envelope.dataAs(io.quack.common.dto.ReadNotify.class);
                if (notify != null) {
                    Platform.runLater(() -> {
                        store.applyReadNotify(notify);
                        if (mainView != null) {
                            mainView.onReadStatusChanged();
                        }
                    });
                }
            }
            case Cmd.FRIEND_REMARK_PUSH -> {
                io.quack.common.model.Friend friend =
                        envelope.dataAs(io.quack.common.model.Friend.class);
                if (friend != null) {
                    Platform.runLater(() -> {
                        store.applyFriendRemark(friend);
                        if (mainView != null) {
                            mainView.refreshConversations();
                        }
                    });
                }
            }
            case Cmd.FRIEND_UPDATED -> {
                io.quack.common.model.User updated = envelope.dataAs(io.quack.common.model.User.class);
                if (updated != null) {
                    Platform.runLater(() -> {
                        store.applyUserUpdate(updated);
                        if (mainView != null) {
                            mainView.refreshConversations();
                            mainView.refreshMe();
                        }
                    });
                }
            }
            case Cmd.CALL_PARTICIPANTS -> {
                io.quack.common.dto.CallParticipants participants =
                        envelope.dataAs(io.quack.common.dto.CallParticipants.class);
                if (participants != null) {
                    Platform.runLater(() -> {
                        if (mainView != null) {
                            mainView.onCallParticipants(participants);
                        }
                    });
                }
            }
            case Cmd.CALL_AUDIO_PUSH -> {
                CallAudioReq audio = envelope.dataAs(CallAudioReq.class);
                if (audio != null && audioCall != null && audio.data() != null) {
                    try {
                        audioCall.play(Base64.getDecoder().decode(audio.data()));
                    } catch (IllegalArgumentException ignored) {
                        // 坏帧直接丢弃
                    }
                }
            }
            case Cmd.CALL_ANSWERED, Cmd.CALL_REJECTED, Cmd.CALL_ENDED -> {
                CallSignal signal = envelope.dataAs(CallSignal.class);
                if (signal != null) {
                    Platform.runLater(() -> {
                        if (mainView != null) {
                            mainView.onCallEvent(signal);
                        }
                    });
                }
            }
            case Cmd.ERROR -> log.warn("服务端错误 code={} msg={}", envelope.code(), envelope.msg());
            default -> log.debug("忽略命令 {}", envelope.cmd());
        }
    }

    /** 推送消息先入队，合并成一个脉冲刷新，离线消息一次来几十条时不会卡 */
    private void enqueuePush(ChatMessage message) {
        synchronized (pendingPushes) {
            pendingPushes.add(message);
        }
        if (!flushScheduled) {
            flushScheduled = true;
            Platform.runLater(this::flushPushes);
        }
    }

    private void flushPushes() {
        flushScheduled = false;
        java.util.List<ChatMessage> batch;
        synchronized (pendingPushes) {
            batch = new java.util.ArrayList<>(pendingPushes);
            pendingPushes.clear();
        }
        if (batch.isEmpty()) {
            return;
        }
        boolean playSound = store.prefs().isNotifySound();
        java.util.Set<String> changed = new java.util.HashSet<>();
        for (ChatMessage message : batch) {
            Conversation conversation = store.applyPush(message);
            changed.add(conversation.key());
            // 群里有人 @我 或 @所有人，给会话打个标记
            if (conversation.getChatType() == io.quack.common.model.ChatType.GROUP
                    && me != null && message.from() != null && !message.from().equals(me.id())
                    && message.content() != null) {
                String content = message.content();
                if (content.contains("@所有人") || content.contains("@" + me.displayName())) {
                    conversation.setMentioned(true);
                }
            }
            if (store.prefs().isMuted(conversation.key())) {
                continue;
            }
            if (playSound) {
                playSound = false;
            }
        }
        // 有新消息的会话要冒到最上面
        store.resort();
        // 批量 RECVACK：告诉服务端“这台设备已经处理到哪个 seq”，它才会推进离线游标
        if (ws.isOpen()) {
            for (String key : changed) {
                Conversation conversation = null;
                for (Conversation candidate : store.conversations()) {
                    if (candidate.key().equals(key)) {
                        conversation = candidate;
                        break;
                    }
                }
                if (conversation != null && conversation.lastSeq() > 0) {
                    ws.send(Cmd.MSG_RECV, new io.quack.common.dto.RecvReq(
                            conversation.getPeerId(), conversation.getChatType().name(),
                            conversation.lastSeq()));
                }
            }
        }
        if (mainView != null) {
            mainView.onMessagesFlushed(changed);
        }
        if (store.prefs().isNotifySound()) {
            java.awt.Toolkit.getDefaultToolkit().beep();
        }
    }

    // ------------------------------------------------------------------
    // 断线与重连
    // ------------------------------------------------------------------

    /** 连接断了：所有还挂着“发送中”的气泡都标失败（它们已经不可能收到 ACK） */
    private void failAllPending() {
        Platform.runLater(() -> {
            for (Conversation conversation : store.conversations()) {
                boolean changed = false;
                for (ChatMessage message : new java.util.ArrayList<>(conversation.messages())) {
                    if (message.id() != null
                            && message.id().startsWith(Conversation.LOCAL_ID_PREFIX)) {
                        changed |= conversation.markFailed(message.clientMsgId());
                    }
                }
                if (changed && mainView != null) {
                    mainView.refreshConversations();
                }
            }
        });
    }

    /** 超时未 ACK 的消息标失败（服务端没回、或连接半开时兜底） */
    private void failStalePending() {
        if (closing) {
            return;
        }
        long deadline = System.currentTimeMillis() - 10_000;
        for (Conversation conversation : store.conversations()) {
            boolean changed = false;
            for (ChatMessage message : new java.util.ArrayList<>(conversation.messages())) {
                if (message.id() != null
                        && message.id().startsWith(Conversation.LOCAL_ID_PREFIX)
                        && message.ts() < deadline) {
                    changed |= conversation.markFailed(message.clientMsgId());
                }
            }
            if (changed && mainView != null) {
                mainView.refreshConversations();
            }
        }
    }
    private void handleClosed(int code, String reason) {
        log.info("连接关闭 code={} reason={}", code, reason);
        if (closing) {
            return;
        }
        // 连接没了，未确认的消息不可能再收到 ACK，直接标失败，别让用户干等
        failAllPending();
        if (code == 4001) {
            long qcId = me == null ? 0 : me.qcId();
            this.credentials = null;
            Platform.runLater(() -> {
                if (mainView != null) {
                    mainView.showBanner("账号在其他地方登录，本端已下线", true);
                }
            });
            // 提示停留一下再回登录页，避免用户还没看清就跳走
            scheduler.schedule(() -> showLogin(String.valueOf(qcId)), 1500, TimeUnit.MILLISECONDS);
            return;
        }
        scheduleReconnect();
    }

    private void scheduleReconnect() {
        if (credentials == null || closing) {
            return;
        }
        // 已经连上/已经排了一次重连，就不要叠加任务（多个失败回调会同时进来）
        if (ws.isOpen() || reconnectScheduled) {
            return;
        }
        int attempt = ++reconnectAttempt;
        if (attempt > 5) {
            Platform.runLater(() -> {
                if (mainView != null) {
                    mainView.showBanner("连接已断开，请重启客户端重新登录", true);
                }
            });
            return;
        }
        reconnectScheduled = true;
        Platform.runLater(() -> {
            if (mainView != null) {
                mainView.showBanner("连接断开，第 " + attempt + " 次重连中…", false);
            }
        });
        scheduler.schedule(() -> {
            if (closing) {
                return;
            }
            ws.connect(serverUrl)
                    .thenCompose(ignored -> ws.request(Cmd.LOGIN, credentials, Duration.ofSeconds(10)))
                    .thenAccept(this::applyLoginResponse)
                    .whenComplete((ignored, error) -> {
                        reconnectScheduled = false;
                        if (error != null) {
                            scheduleReconnect();
                        }
                    });
        }, Math.min(3L * attempt, 15L), TimeUnit.SECONDS);
    }

    public void shutdown() {
        closing = true;
        ws.close();
        scheduler.shutdownNow();
    }
}
