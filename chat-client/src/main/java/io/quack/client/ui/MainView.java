package io.quack.client.ui;

import io.quack.client.AppContext;
import io.quack.client.store.Conversation;
import io.quack.common.dto.HistoryResp;
import io.quack.common.dto.TypingNotify;
import io.quack.common.model.ChatMessage;
import io.quack.common.model.ChatType;
import io.quack.common.model.Friend;
import javafx.animation.FadeTransition;
import javafx.animation.PauseTransition;
import javafx.beans.binding.Bindings;
import javafx.collections.ListChangeListener;
import javafx.collections.transformation.FilteredList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Window;
import javafx.util.Duration;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * 主界面三段式：最左导航栏（聊天 / 通讯录）+ 中间的列表 + 右侧内容区。
 *
 * <p>「聊天」按最近消息列会话；「通讯录」列全部联系人和群，加好友入口在最上方。
 * 原来的「＋」按钮位置改成了搜索框。
 */
public class MainView extends BorderPane {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm")
            .withZone(ZoneId.systemDefault());

    private final AppContext ctx;
    private final LeftRail rail;
    private final VBox chatList;
    private final ContactsPane contacts;
    private final StackPane listHost = new StackPane();
    private SplitHandle sidebarHandle;
    private double sidebarWidth = 260;
    private final ListView<Conversation> conversationList = new ListView<>();
    private final FilteredList<Conversation> filteredConversations;
    private final ChatPane chatPane;
    private final FriendRequestPane requestPane;
    private final StackPane contentHost = new StackPane();
    private final Label banner = new Label();
    /** 顶部提示停留 2 秒后自动淡出（错误提示也一样，不能一直挂着） */
    private final PauseTransition bannerTimer = new PauseTransition(Duration.seconds(2));
    private final FadeTransition bannerFade = new FadeTransition(Duration.millis(260));

    /** 必填：store 比 MainView 活得久，退出登录/重连重建界面时必须把监听摘掉 */
    private ListChangeListener<io.quack.common.model.FriendRequest> requestWatcher;

    public MainView(AppContext ctx) {
        this.ctx = ctx;
        getStyleClass().add("main-root");
        // 应用上次设置的界面字号
        setStyle("-fx-font-size: " + ctx.store().prefs().getFontSize() + "px;");

        filteredConversations = new FilteredList<>(ctx.store().conversations(), conversation -> true);
        chatPane = new ChatPane(ctx);
        chatPane.setOnOpenInfo(() -> openInfo(chatPane.current()));
        requestPane = new FriendRequestPane(ctx);
        contacts = new ContactsPane(ctx, this::openConversation, this::showRequests);

        contentHost.getChildren().add(chatPane);
        contentHost.getStyleClass().add("content-host");

        chatList = buildChatList();
        listHost.getChildren().add(chatList);
        listHost.getStyleClass().add("list-host");
        applySidebarWidth(sidebarWidth);

        // 会话列表和聊天区之间的分割线：可拖动、有最小宽度（180）和最大宽度
        // 最大宽度要给「导航栏 74 + 分割线 5 + 聊天区最小 420」留够空间，否则输入区工具栏会被挤掉
        sidebarHandle = new SplitHandle(SplitHandle.Axis.HORIZONTAL, listHost, 180,
                () -> Math.max(180, getWidth() - 74 - 5 - 420), 260);
        listHost.widthProperty().addListener((obs, old, value) -> {
            if (value.doubleValue() > 0) {
                sidebarWidth = value.doubleValue();
            }
        });
        // 窗口变化时实时夹取，内容跟着一起重排（不冻结）
        widthProperty().addListener((obs, old, value) -> {
            double max = Math.max(180, value.doubleValue() - 74 - 5 - 420);
            applySidebarWidth(Math.min(sidebarWidth, max));
        });

        rail = new LeftRail(ctx, this::switchSection);

        HBox left = new HBox(rail, listHost, sidebarHandle);
        left.getStyleClass().add("left-region");
        setLeft(left);
        setTop(buildBanner());
        setCenter(contentHost);

        conversationList.getSelectionModel().selectedItemProperty().addListener((obs, old, selected) -> {
            if (selected != null) {
                selected.clearUnread();
                selected.setMentioned(false);
                showChat();
                chatPane.open(selected);
            }
        });

        requestWatcher = change -> requestPane.refresh();
        ctx.store().incomingRequests().addListener(requestWatcher);
        bannerFade.setNode(banner);
        bannerFade.setFromValue(1);
        bannerFade.setToValue(0);
        bannerTimer.setOnFinished(event -> bannerFade.playFromStart());
        bannerFade.setOnFinished(event -> hideBanner());

        applyFilter("");
        if (!filteredConversations.isEmpty()) {
            conversationList.getSelectionModel().selectFirst();
        } else {
            showRequests();
        }
    }

    // ------------------------------------------------------------------
    // 左侧两栏
    // ------------------------------------------------------------------

    /** 侧栏宽度：统一走这里设置，保证 min/pref/max 一致 */
    private void applySidebarWidth(double width) {
        double size = Math.max(180, width);
        listHost.setMinWidth(size);
        listHost.setPrefWidth(size);
        listHost.setMaxWidth(size);
    }
    private VBox buildChatList() {
        VBox pane = new VBox(10);
        pane.getStyleClass().add("sidebar");
        pane.setPadding(new Insets(16, 10, 10, 10));

        Label title = new Label("消息");
        title.getStyleClass().add("sidebar-title");

        TextField search = new TextField();
        search.setPromptText("搜索好友 / 群");
        search.getStyleClass().add("search-field");
        search.textProperty().addListener((obs, old, keyword) -> applyFilter(keyword));

        conversationList.setItems(filteredConversations);
        conversationList.setCellFactory(view -> new ConversationCell());
        conversationList.getStyleClass().add("conversation-list");
        conversationList.setFixedCellSize(62);
        VBox.setVgrow(conversationList, Priority.ALWAYS);

        pane.getChildren().addAll(title, search, conversationList);
        return pane;
    }

    private void switchSection(boolean chats) {
        // 聊天 ↔ 通讯录：交叉淡入，旧面板轻微上移离场
        io.quack.client.ui.motion.Motion.swap(listHost, chats ? chatList : contacts);
    }

    private Label buildBanner() {
        banner.getStyleClass().add("banner");
        banner.setMaxWidth(Double.MAX_VALUE);
        banner.setVisible(false);
        banner.setManaged(false);
        return banner;
    }

    private void showChat() {
        if (contentHost.getChildren().size() != 1 || contentHost.getChildren().get(0) != chatPane) {
            io.quack.client.ui.motion.Motion.swap(contentHost, chatPane);
        }
    }

    /** 从通讯录点某个联系人 */
    private void openConversation(Conversation conversation) {
        if (conversationList.getItems().contains(conversation)) {
            conversationList.getSelectionModel().select(conversation);
        } else {
            showChat();
            chatPane.open(conversation);
        }
    }

    public void showRequests() {
        io.quack.client.ui.motion.Motion.swap(contentHost, requestPane);
        requestPane.refresh();
    }

    // ------------------------------------------------------------------
    // 过滤 / 菜单 / 回调
    // ------------------------------------------------------------------

    private void applyFilter(String keyword) {
        String normalized = keyword == null ? "" : keyword.trim().toLowerCase();
        boolean showHidden = ctx.store().prefs().isShowHiddenSessions();
        filteredConversations.setPredicate(conversation -> {
            if (!showHidden && ctx.store().prefs().isHidden(conversation.key())) {
                return false;
            }
            return normalized.isEmpty() || conversation.getTitle().toLowerCase().contains(normalized);
        });
    }

    /** 退出登录 / 重连重建前释放监听，避免旧界面被 store 一直引用 */
    public void dispose() {
        if (requestWatcher != null) {
            ctx.store().incomingRequests().removeListener(requestWatcher);
            requestWatcher = null;
        }
        if (contacts != null) {
            contacts.dispose();
        }
        if (chatPane != null) {
            chatPane.dispose();
        }
        bannerTimer.stop();
        bannerFade.stop();
    }

    /** 资料修改后刷新左下角头像/昵称 */
    /** 好友资料更新后强制重绘列表单元格（头像不是可观察属性，需要手动 refresh） */
    public void refreshConversations() {
        if (conversationList != null) {
            conversationList.refresh();
        }
        // 聊天页头部和消息气泡里的发送者头像也要跟着变，否则会出现「列表变了、里面没变」
        if (chatPane != null) {
            chatPane.refreshHeader();
            chatPane.refreshMessages();
        }
    }

    public void refreshMe() {
        if (rail != null) {
            rail.refreshAccount();
        }
    }

    /** 通讯录是“每次变化整表重建”的实现，因此状态变化后要显式重建 */
    public void refreshContacts() {
        if (contacts != null) {
            contacts.rebuild();
        }
    }

    public void refreshFilter() {
        applyFilter("");
    }

    public void onGroupUpdated(io.quack.common.model.GroupDetail detail) {
        refreshFilter();
    }

    public void onGroupLeft(String groupId) {
        if (chatPane.current() != null && groupId.equals(chatPane.current().getPeerId())) {
            showRequests();
        }
        refreshFilter();
    }

    public void openInfo(Conversation conversation) {
        if (conversation == null) {
            return;
        }
        Window owner = getScene() == null ? null : getScene().getWindow();
        if (conversation.getChatType() == ChatType.GROUP) {
            GroupInfoView.show(owner, ctx, conversation, this::refreshFilter, chatPane::insertText,
                    message -> {
                        openConversation(conversation);
                        javafx.application.Platform.runLater(() -> chatPane.locateMessage(message));
                    });
        } else {
            ProfileView.show(owner, ctx, conversation.getPeerId(), this::openPrivateChat);
        }
    }

    private void openPrivateChat(String peerId) {
        for (Conversation conversation : ctx.store().conversations()) {
            if (conversation.getChatType() == ChatType.PRIVATE
                    && conversation.getPeerId().equals(peerId)) {
                openConversation(conversation);
                return;
            }
        }
    }

    private ContextMenu conversationMenu(Conversation conversation) {
        ContextMenu menu = new ContextMenu();
        Popups.install(menu);
        String key = conversation.key();
        var prefs = ctx.store().prefs();

        MenuItem info = new MenuItem(conversation.getChatType() == ChatType.GROUP ? "详细信息" : "查看资料");
        info.setOnAction(e -> openInfo(conversation));
        menu.getItems().add(info);

        MenuItem read = new MenuItem("标为已读");
        read.setOnAction(e -> conversation.clearUnread());
        menu.getItems().add(read);

        MenuItem pin = new MenuItem(prefs.isPinned(key) ? "取消置顶" : "置顶会话");
        pin.setOnAction(e -> {
            boolean next = !prefs.isPinned(key);
            prefs.setPinned(key, next);
            ctx.store().resort();
            ctx.store().conversations().remove(conversation);
            ctx.store().conversations().add(next ? 0 : ctx.store().conversations().size(), conversation);
            refreshFilter();
        });
        menu.getItems().add(pin);

        MenuItem mute = new MenuItem(prefs.isMuted(key) ? "取消免打扰" : "消息免打扰");
        mute.setOnAction(e -> prefs.setMuted(key, !prefs.isMuted(key)));
        menu.getItems().add(mute);

        MenuItem hide = new MenuItem("隐藏会话");
        hide.setOnAction(e -> {
            prefs.setHidden(key, true);
            refreshFilter();
        });
        menu.getItems().add(hide);

        MenuItem clear = new MenuItem("删除聊天记录");
        clear.setOnAction(e -> ConfirmDialog.confirm(getScene() == null ? null : getScene().getWindow(),
                "删除聊天记录", "只删除本机记录，对方不受影响。", "删除",
                () -> ctx.store().clearMessages(conversation)));
        menu.getItems().add(clear);

        if (conversation.getChatType() == ChatType.GROUP) {
            MenuItem quit = new MenuItem("退出群聊");
            quit.setOnAction(e -> ConfirmDialog.confirm(getScene() == null ? null : getScene().getWindow(),
                    "退出群聊", "退出后将不再接收该群消息。", "退出",
                    () -> ctx.quitGroup(conversation.getPeerId())));
            menu.getItems().add(quit);
        }
        return menu;
    }

    public void onMessageArrived(Conversation conversation) {
        if (conversation == chatPane.current()) {
            conversation.clearUnread();
            chatPane.scrollToBottom();
        }
    }

    /** 一批推送消息已经落到 store，界面统一刷新一次 */
    public void onMessagesFlushed(java.util.Set<String> changedKeys) {
        Conversation conversation = chatPane.current();
        if (conversation != null && changedKeys != null && changedKeys.contains(conversation.key())) {
            // 只有“当前打开的会话”来新消息才滚到底、清未读、顺手把已读位点推进
            conversation.clearUnread();
            conversation.setMentioned(false);
            chatPane.scrollToBottom();
            ctx.markRead(conversation);
        }
    }

    /** 增量同步结果：合并到对应会话，并刷新当前会话的已读状态 */
    public void onSync(Conversation conversation,
                       io.quack.common.dto.SyncResp resp) {
        int added = ctx.store().applySync(conversation, resp.messages());
        if (conversation == chatPane.current()) {
            if (added > 0) {
                chatPane.scrollToBottom();
            }
            chatPane.refreshReadStatus();
            ctx.markRead(conversation);
        }
    }

    public void onTyping(TypingNotify notify) {
        chatPane.showTyping(notify);
    }

    public void onFriendAdded(Friend friend) {
        for (Conversation conversation : conversationList.getItems()) {
            if (conversation.getChatType() == ChatType.PRIVATE
                    && conversation.getPeerId().equals(friend.userId())) {
                conversationList.getSelectionModel().select(conversation);
                showChat();
                break;
            }
        }
    }

    public void onRequestsChanged() {
        requestPane.refresh();
    }

    /** 收到来电 */
    public void onIncomingCall(io.quack.common.model.CallSignal signal) {
        CallDialog.incoming(getScene() == null ? null : getScene().getWindow(), ctx, signal);
    }

    /** 已读状态变化：刷新消息列表 */
    public void onReadStatusChanged() {
        if (chatPane != null) {
            chatPane.refreshReadStatus();
        }
    }

    /** 群通话参与者变化 */
    public void onCallParticipants(io.quack.common.dto.CallParticipants participants) {
        CallDialog.onParticipants(participants);
    }

    /** 通话状态变化（接听/拒绝/挂断） */
    public void onCallEvent(io.quack.common.model.CallSignal signal) {
        CallDialog.onEvent(signal);
    }

    public void onConnectionRestored() {
        hideBanner();
    }

    public void showBanner(String text, boolean fatal) {
        bannerTimer.stop();
        bannerFade.stop();
        banner.setOpacity(1);
        banner.setText(text);
        banner.getStyleClass().remove("banner-error");
        if (fatal) {
            banner.getStyleClass().add("banner-error");
        }
        banner.setManaged(true);
        banner.setVisible(true);
        // 不管是不是错误，都在 2 秒后淡出；错误用红色已经足够醒目，不能一直挂在顶部
        bannerTimer.playFromStart();
    }

    /** 离线模式：常驻横幅，点击回到登录页重新在线登录 */
    public void showOfflineBanner() {
        bannerTimer.stop();
        bannerFade.stop();
        banner.setOpacity(1);
        banner.getStyleClass().remove("banner-error");
        banner.setText("离线模式：只显示本机缓存 · 点击这里重新登录");
        banner.setCursor(javafx.scene.Cursor.HAND);
        banner.setOnMouseClicked(event -> ctx.leaveOffline());
        banner.setManaged(true);
        banner.setVisible(true);
    }

    private void hideBanner() {
        bannerTimer.stop();
        bannerFade.stop();
        banner.setOpacity(1);
        banner.setVisible(false);
        banner.setManaged(false);
    }
    /** 会话列表单元格：节点只创建一次，updateItem 里只改文本（避免滚动时反复 new / bind） */
    private final class ConversationCell extends ListCell<Conversation> {

        private final Label avatar = new Label();
        private final Label title = new Label();
        private final Label preview = new Label();
        private final Label time = new Label();
        private final Label unread = new Label();
        private final Label pin = new Label();
        private final Label onlineDot = new Label("●");
        private final HBox row;
        private String lastPeer;
        private String lastAvatar;
        private boolean widthBound;

        ConversationCell() {
            avatar.getStyleClass().add("avatar");
            title.getStyleClass().add("conv-title");
            preview.getStyleClass().add("conv-preview");
            time.getStyleClass().add("conv-time");
            unread.getStyleClass().add("unread-badge");
            onlineDot.getStyleClass().add("presence-dot");

            pin.setGraphic(Icons.of("pin", 12));
            pin.getStyleClass().add("pin-mark");
            HBox titleRow = new HBox(4, title, pin);
            titleRow.setAlignment(Pos.CENTER_LEFT);
            titleRow.setMinWidth(0);
            VBox texts = new VBox(3, titleRow, preview);
            HBox.setHgrow(texts, Priority.ALWAYS);
            texts.setMaxWidth(Double.MAX_VALUE);
            texts.setMinWidth(0);
            title.setMinWidth(0);
            title.setMaxWidth(Double.MAX_VALUE);
            preview.setMinWidth(0);
            preview.setMaxWidth(Double.MAX_VALUE);

            // 未读数：红底白字，叠在头像右上角；在线小绿点叠在右下角
            StackPane avatarBox = new StackPane(avatar, unread, onlineDot);
            StackPane.setAlignment(unread, Pos.TOP_RIGHT);
            unread.setTranslateX(6);
            unread.setTranslateY(-4);
            StackPane.setAlignment(onlineDot, Pos.BOTTOM_RIGHT);
            onlineDot.setTranslateX(2);
            onlineDot.setTranslateY(2);
            avatarBox.setMinSize(44, 44);
            avatarBox.setPrefSize(44, 44);
            avatarBox.setMaxSize(44, 44);

            VBox right = new VBox(6, time);
            right.setAlignment(Pos.TOP_RIGHT);
            right.setMinWidth(42);
            right.setPrefWidth(42);

            row = new HBox(10, avatarBox, texts, right);
            row.setAlignment(Pos.CENTER_LEFT);
            row.setPadding(new Insets(3, 7, 3, 7));
            row.setMinWidth(0);
            setGraphic(null);

            setOnContextMenuRequested(event -> {
                Conversation item = getItem();
                if (item != null) {
                    conversationMenu(item).show(this, event.getScreenX(), event.getScreenY());
                    event.consume();
                }
            });
        }

        @Override
        protected void updateItem(Conversation conversation, boolean empty) {
            super.updateItem(conversation, empty);
            if (empty || conversation == null) {
                setGraphic(null);
                return;
            }
            setGraphic(row);

            if (!widthBound && getListView() != null) {
                widthBound = true;
                row.prefWidthProperty().bind(getListView().widthProperty().subtract(34));
            }

            avatar.setText(Avatars.initial(conversation.getTitle()));
            if (!conversation.getPeerId().equals(lastPeer)
                    || !java.util.Objects.equals(conversation.avatar(), lastAvatar)) {
                lastPeer = conversation.getPeerId();
                lastAvatar = conversation.avatar();
                Avatars.apply(avatar, conversation.getPeerId(), conversation.avatar());
            }

            title.setText(conversation.getTitle());
            pin.setVisible(ctx.store().prefs().isPinned(conversation.key()));
            preview.setText(previewOf(conversation));
            time.setText(timeOf(conversation));

            int count = conversation.getUnread();
            unread.setText(String.valueOf(count));
            unread.setVisible(count > 0);
            unread.setManaged(count > 0);
            // 群聊没有“在线”概念，只有私聊显示在线小绿点
            boolean showOnline = conversation.getChatType() == ChatType.PRIVATE
                    && conversation.isOnline();
            onlineDot.setVisible(showOnline);
            onlineDot.setManaged(showOnline);
        }

        private String previewOf(Conversation conversation) {
            String prefix = conversation.isMentioned() ? "[有人@你] " : "";
            if (conversation.messages().isEmpty()) {
                return prefix + (conversation.getChatType() == ChatType.GROUP ? "还没有人说话" : "打个招呼吧");
            }
            ChatMessage last = conversation.messages().get(conversation.messages().size() - 1);
            String content = last.content() == null ? "" : last.content().replace('\n', ' ');
            String text = switch (last.msgType()) {
                case IMAGE -> "[图片]";
                case VIDEO -> "[视频]";
                case FILE -> "[文件]";
                case GIFT -> "[礼物]";
                default -> content.length() > 16 ? content.substring(0, 16) + "…" : content;
            };
            return prefix + text;
        }

        private String timeOf(Conversation conversation) {
            if (conversation.messages().isEmpty()) {
                return "";
            }
            ChatMessage last = conversation.messages().get(conversation.messages().size() - 1);
            return TIME.format(Instant.ofEpochMilli(last.ts()));
        }
    }
}
