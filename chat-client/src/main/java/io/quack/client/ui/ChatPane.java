package io.quack.client.ui;

import io.quack.client.AppContext;
import io.quack.client.ClientErrors;
import io.quack.client.store.Conversation;
import io.quack.common.dto.HistoryResp;
import io.quack.common.dto.TypingNotify;
import io.quack.common.model.ChatMessage;
import io.quack.common.model.ChatType;
import io.quack.common.model.MsgType;
import io.quack.common.model.User;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.TextArea;
import javafx.scene.control.Tooltip;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.stage.FileChooser;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** 右侧聊天区：会话头 + 消息列表 + 输入区 */
public class ChatPane extends BorderPane {

    private final AppContext ctx;

    /** 输入区最小高度：要能放下输入框最小高度 + 工具栏，否则工具栏会被裁掉 */
    private static final double COMPOSER_MIN = 116;
    private static final double COMPOSER_INITIAL = 136;
    /** 消息区最小高度 */
    private static final double MESSAGES_MIN = 160;

    private final Label avatar = new Label();
    private final Label titleLabel = new Label("选择一个会话");
    private final Label subtitleLabel = new Label("从左侧选择好友或群开始聊天");
    private final ListView<ChatMessage> messageList = new ListView<>();
    private final TextArea input = new TextArea();
    private final javafx.stage.Popup mentionPopup = new javafx.stage.Popup();
    private final VBox mentionList = new VBox(0);
    private final javafx.scene.control.ScrollPane mentionScroll =
            new javafx.scene.control.ScrollPane(mentionList);
    private java.util.List<io.quack.common.model.GroupMember> mentionMembers;
    private String mentionGroupId;
    /** 当前正在输入的 @ 片段起点 */
    private int mentionStart = -1;
    private final Label emptyState = new Label("还没有消息，说点什么吧");
    /** 正在检查/展示群公告的群，避免重复弹 */
    private final java.util.Set<String> announcementOpen =
            java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final PauseTransition typingIndicator = new PauseTransition(Duration.seconds(3));
    /** 输入草稿防抖：停止输入 600ms 后落本地库 */
    private final PauseTransition draftTimer = new PauseTransition(Duration.millis(600));
    /** 所有气泡共享同一个宽度属性，窗口缩放时只算一次 */
    private final javafx.beans.property.ReadOnlyDoubleWrapper bubbleWidth =
            new javafx.beans.property.ReadOnlyDoubleWrapper(320);

    private Button giftButton;
    private Button phraseButton;
    private Button searchButton;
    private SplitHandle composerHandle;
    private Conversation current;
    private long lastTypingSentAt;
    /**
     * 每个消息 id -> 已读状态文案。
     *
     * <p>之前是每个可见 cell 各自倒着扫一遍消息列表，滚动时是 O(n²)；
     * 现在整批算一次，cell 只做一次 map 查询。
     */
    private final java.util.Map<String, String> readStatusCache = new java.util.HashMap<>();
    private boolean readStatusDirty = true;
    /** 用同一个实例监听“当前会话消息变化”，切换会话时必须摘掉旧的，否则会泄漏 */
    private final javafx.collections.ListChangeListener<ChatMessage> messageWatcher =
            change -> readStatusDirty = true;
    /** 滚动到底部的缓动动画：只保留一个，避免连续发消息时叠出一堆 Timeline */
    private javafx.animation.Timeline scrollAnimation;
    /** 双击标题 / 点“⋯”时打开资料页（由 MainView 注入） */
    private Runnable onOpenInfo = () -> {
    };

    public ChatPane(AppContext ctx) {
        this.ctx = ctx;
        getStyleClass().add("chat-pane");
        // 聊天区再窄也要放得下输入区工具栏（7 个动作 + 发送）
        setMinWidth(380);

        setTop(buildHeader());
        setCenter(buildMessages());

        // 消息区 / 分割线 / 输入区。输入区高度由分割线控制，并且必须始终留出
        // 「消息区至少 MESSAGES_MIN + 工具栏完整可见」的空间，否则底部按钮会被窗口裁掉。
        VBox composer = buildComposer();
        composer.setMinHeight(COMPOSER_MIN);
        composer.setPrefHeight(COMPOSER_INITIAL);
        composerHandle = new SplitHandle(SplitHandle.Axis.VERTICAL, composer, COMPOSER_MIN,
                this::maxComposerHeight, COMPOSER_INITIAL);
        setBottom(new VBox(composerHandle, composer));

        // 窗口变矮时立刻把输入区夹回来（之前没有这一步，缩小窗口后发送按钮只剩一半）
        heightProperty().addListener((obs, old, value) -> composerHandle.clamp(maxComposerHeight()));
        javafx.application.Platform.runLater(() -> composerHandle.clamp(maxComposerHeight()));

        typingIndicator.setOnFinished(e -> refreshSubtitle());
        draftTimer.setOnFinished(e -> {
            if (current != null) {
                ctx.store().saveDraft(current.key(), input.getText());
            }
        });
    }

    /** 输入区最多能占多高：不超过可用高度的 55%，且必须给消息区留下最小高度 */
    private double maxComposerHeight() {
        double height = getHeight();
        double headerHeight = getTop() == null ? 0 : Math.max(0, getTop().prefHeight(-1));
        double available = height - headerHeight - MESSAGES_MIN - 5;
        double half = Math.max(COMPOSER_MIN, height * 0.55);
        return Math.max(COMPOSER_MIN, Math.min(half, available));
    }

    private HBox buildHeader() {
        avatar.getStyleClass().addAll("avatar", "chat-avatar");
        avatar.setMinSize(38, 38);
        avatar.setPrefSize(38, 38);
        avatar.setMaxSize(38, 38);

        titleLabel.getStyleClass().add("chat-title");
        subtitleLabel.getStyleClass().add("chat-subtitle");
        VBox texts = new VBox(2, titleLabel, subtitleLabel);
        HBox.setHgrow(texts, Priority.ALWAYS);

        Label more = new Label("⋯");
        more.getStyleClass().add("chat-more");
        more.setCursor(javafx.scene.Cursor.HAND);
        more.setOnMouseClicked(event -> onOpenInfo.run());

        HBox header = new HBox(12, avatar, texts, more);
        header.setAlignment(Pos.CENTER_LEFT);
        header.getStyleClass().add("chat-header");
        header.setPadding(new Insets(14, 18, 14, 18));
        header.setCursor(javafx.scene.Cursor.HAND);
        header.setOnMouseClicked(event -> {
            if (event.getClickCount() == 2) {
                onOpenInfo.run();
            }
        });
        return header;
    }

    private StackPane buildMessages() {
        messageList.getStyleClass().add("message-list");
        messageList.setMinHeight(160);
        messageList.setFocusTraversable(false);
        messageList.setPlaceholder(emptyState);
        // 气泡宽度实时跟随列表宽度（拖窗口时内容一起重排，不冻结）
        Runnable applyBubbleWidth = () -> bubbleWidth.set(
                Math.max(220.0, messageList.getWidth() * 0.62));
        messageList.widthProperty().addListener((obs, old, value) -> applyBubbleWidth.run());
        applyBubbleWidth.run();
        messageList.setCellFactory(view -> new MessageCell(
                () -> ctx.store().me() == null ? null : ctx.store().me().id(),
                bubbleWidth.getReadOnlyProperty(),
                this::messageMenu,
                url -> ImageViewerDialog.show(getScene() == null ? null : getScene().getWindow(), url),
                url -> ctx.playVideo(url),
                () -> ctx.store().prefs().isAutoOpenDownload(),
                this::readStatusOf));

        emptyState.getStyleClass().add("empty-state");
        emptyState.setGraphic(Icons.of("chat", 28));
        emptyState.setContentDisplay(javafx.scene.control.ContentDisplay.TOP);
        emptyState.setGraphicTextGap(10);
        StackPane wrapper = new StackPane(messageList);
        wrapper.getStyleClass().add("message-wrapper");
        return wrapper;
    }

    private VBox buildComposer() {
        input.setPromptText("输入消息… Shift+Enter 换行");
        input.setWrapText(true);
        input.setPrefRowCount(2);
        // 输入框是唯一可伸缩的块：输入区变矮时先压它，工具栏永远完整显示
        input.setMinHeight(46);
        input.setMaxHeight(Double.MAX_VALUE);
        VBox.setVgrow(input, Priority.ALWAYS);
        input.getStyleClass().add("message-input");
        input.setOnKeyPressed(event -> {
            if (event.getCode() == KeyCode.ENTER && !event.isShiftDown()) {
                event.consume();
                send();
            }
        });
        input.textProperty().addListener((obs, old, text) -> {
            // 先更新 @ 弹窗：删掉 @ 时也要能收起来
            updateMentionPopup();
            if (text == null || text.isBlank()) {
                return;
            }
            long now = System.currentTimeMillis();
            // 只有用户真的在输入框里打字才发“正在输入”，恢复草稿不算
            if (input.isFocused() && current != null && now - lastTypingSentAt > 2000) {
                lastTypingSentAt = now;
                ctx.sendTyping(current);
            }
            if (current != null) {
                draftTimer.playFromStart();
            }
            updateMentionPopup();
        });

        Button sendButton = new Button("发送");
        sendButton.getStyleClass().add("send-button");
        sendButton.setMinWidth(64);
        sendButton.setDefaultButton(false);
        sendButton.setOnAction(event -> send());
        io.quack.client.ui.motion.Motion.pressable(sendButton, 0.94);



        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        giftButton = actionButton("gift", "送礼物", this::openGift);
        searchButton = actionButton("search", "查找聊天记录", this::openSearch);
        phraseButton = actionButton("chat", "快捷短语", this::openQuickPhrases);

        HBox actions = new HBox(4,
                actionButton("file", "发送文件", () -> pickFile(false)),
                actionButton("image", "发送图片", () -> pickFile(true)),
                actionButton("video", "发送视频", this::pickVideo),
                giftButton,
                actionButton("phone", "语音通话", this::startCall),
                searchButton,
                phraseButton);

        HBox toolbar = new HBox(10, actions, spacer, sendButton);
        toolbar.setAlignment(Pos.CENTER_LEFT);
        toolbar.setPadding(new Insets(6, 2, 0, 4));
        // 工具栏不参与压缩：按钮不能被裁成一半
        toolbar.setMinHeight(Region.USE_PREF_SIZE);

        VBox composer = new VBox(6, input, toolbar);
        composer.getStyleClass().add("composer");
        composer.setPadding(new Insets(10, 16, 14, 16));
        return composer;
    }

    /** 只给「最后一条自己的消息」显示已读状态 */

    /** 输入 @ 时弹出成员选择；继续输入则过滤，点选后插入 @名字 */
    private void updateMentionPopup() {

        if (current == null || current.getChatType() != ChatType.GROUP) {
            hideMentionPopup();
            return;
        }
        String text = input.getText() == null ? "" : input.getText();
        MentionParser.Token token = MentionParser.tokenAt(text, input.getCaretPosition());
        if (token == null) {
            hideMentionPopup();
            return;
        }
        int at = token.start();
        String query = token.query();
        mentionStart = at;

        if (mentionMembers == null || !current.getPeerId().equals(mentionGroupId)) {
            mentionGroupId = current.getPeerId();
            mentionMembers = java.util.List.of();
            ctx.loadGroupDetail(mentionGroupId).whenComplete((detail, error) -> Platform.runLater(() -> {
                if (error == null && detail != null) {
                    mentionMembers = detail.members();
                    updateMentionPopup();
                }
            }));
        }

        java.util.List<javafx.scene.Node> rows = new java.util.ArrayList<>();
        rows.add(mentionRow("所有人", "广播给全群", "@所有人 "));
        for (io.quack.common.model.GroupMember member : mentionMembers) {
            String name = member.nickname() == null ? member.userId() : member.nickname();
            if (!query.isBlank() && !name.toLowerCase().contains(query.toLowerCase())) {
                continue;
            }
            rows.add(mentionRow(name, member.isOwner() ? "群主" : "成员", "@" + name + " "));
        }
        if (rows.size() <= 1 && !query.isBlank()) {
            hideMentionPopup();
            return;
        }

        mentionList.getChildren().setAll(rows);
        if (!mentionPopup.isShowing()) {
            mentionScroll.setFitToWidth(true);
            mentionScroll.setHbarPolicy(javafx.scene.control.ScrollPane.ScrollBarPolicy.NEVER);
            mentionScroll.setVbarPolicy(javafx.scene.control.ScrollPane.ScrollBarPolicy.AS_NEEDED);
            mentionScroll.getStyleClass().add("mention-popup");
            // 固定最多显示 10 行，超出用滚动条
            double rowHeight = 30;
            int visible = Math.min(rows.size(), 10);
            double height = visible * rowHeight + 10;
            mentionScroll.setPrefWidth(200);
            mentionScroll.setStyle("-fx-font-family: \"Microsoft YaHei UI\";");
            // 弹窗是独立窗口，不会自动继承主窗口样式表，这里手动带上
            if (input.getScene() != null) {
                for (String sheet : input.getScene().getStylesheets()) {
                    if (!mentionScroll.getStylesheets().contains(sheet)) {
                        mentionScroll.getStylesheets().add(sheet);
                    }
                }
            }
            mentionScroll.setPrefHeight(height);
            mentionScroll.setMaxHeight(height);

            mentionPopup.getContent().setAll(mentionScroll);
            mentionPopup.setAutoHide(true);

            var bounds = input.localToScreen(input.getBoundsInLocal());
            if (bounds != null) {
                var screen = javafx.stage.Screen.getScreensForRectangle(
                                bounds.getMinX(), bounds.getMinY(), 1, 1).stream()
                        .findFirst().orElse(javafx.stage.Screen.getPrimary())
                        .getVisualBounds();
                double x = Math.min(bounds.getMinX() + 4, screen.getMaxX() - 200);
                // 底边停在输入框上沿上面 4px，绝不超过输入框、也不盖住文字
                double y = bounds.getMinY() - height - 4;
                if (y < screen.getMinY() + 8) {
                    double allowed = bounds.getMinY() - screen.getMinY() - 12;
                    height = Math.max(rowHeight + 6, Math.min(height, allowed));
                    y = screen.getMinY() + 8;
                }

                mentionPopup.show(input, Math.max(screen.getMinX() + 4, x), Math.max(screen.getMinY() + 4, y));
            }
        }
    }

    private javafx.scene.Node mentionRow(String name, String role, String insert) {
        Label title = new Label(name);
        title.getStyleClass().add("mention-name");
        Label sub = new Label(role);
        sub.getStyleClass().add("mention-role");
        VBox texts = new VBox(0, title, sub);
        HBox row = new HBox(6, texts);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(3, 8, 3, 8));
        row.setMinHeight(30);
        row.setPrefHeight(30);
        row.getStyleClass().add("mention-row");
        row.setMinWidth(160);
        row.setCursor(javafx.scene.Cursor.HAND);
        row.setOnMouseClicked(e -> {
            String text = input.getText() == null ? "" : input.getText();
            int caret = Math.max(mentionStart + 1, Math.min(input.getCaretPosition(), text.length()));
            if (mentionStart >= 0 && mentionStart <= caret) {
                String next = text.substring(0, mentionStart) + insert + text.substring(caret);
                input.setText(next);
                input.positionCaret(mentionStart + insert.length());
            }
            hideMentionPopup();
            input.requestFocus();
        });
        return row;
    }

    private void hideMentionPopup() {
        mentionStart = -1;
        if (mentionPopup.isShowing()) {
            mentionPopup.hide();
        }
    }
    private String readStatusOf(ChatMessage message) {
        if (message == null || message.id() == null) {
            return "";
        }
        if (readStatusDirty) {
            rebuildReadStatus();
        }
        return readStatusCache.getOrDefault(message.id(), "");
    }

    /**
     * 重算“已读”缓存：只给最后一条自己的正式消息打标记。
     *
     * <p>整个会话扫一次（O(n)），而不是每个 cell 扫一次（O(n²)）。
     */
    private void rebuildReadStatus() {
        readStatusDirty = false;
        readStatusCache.clear();
        Conversation conversation = current;
        io.quack.common.model.User me = ctx.store().me();
        if (conversation == null || me == null) {
            return;
        }
        java.util.List<ChatMessage> messages = conversation.messages();
        ChatMessage lastOwn = null;
        for (int i = messages.size() - 1; i >= 0; i--) {
            ChatMessage message = messages.get(i);
            if (message.from() == null || !message.from().equals(me.id())) {
                continue;
            }
            String id = message.id();
            if (id == null || id.startsWith(Conversation.LOCAL_ID_PREFIX)
                    || id.startsWith(Conversation.FAILED_ID_PREFIX)) {
                continue;
            }
            lastOwn = message;
            break;
        }
        final ChatMessage last = lastOwn;
        if (last == null || last.id() == null) {
            return;
        }
        if (conversation.getChatType() == ChatType.PRIVATE) {
            // 三态：发送中（本地未 ACK）→ 已送达（服务端已提交）→ 已读（对方上报位点）
            boolean read;
            if (last.seq() > 0) {
                long readSeq = ctx.store().readSeq(conversation.key(), conversation.getPeerId());
                read = readSeq > 0
                        ? readSeq >= last.seq()
                        : ctx.store().readTs(conversation.key(), conversation.getPeerId()) >= last.ts();
            } else {
                read = ctx.store().readTs(conversation.key(), conversation.getPeerId()) >= last.ts();
            }
            readStatusCache.put(last.id(), read ? "已读" : "已送达");
        } else {
            long count = last.seq() > 0
                    ? ctx.store().readSeqMap(conversation.key()).values().stream()
                            .filter(seq -> seq >= last.seq()).count()
                    : ctx.store().readMap(conversation.key()).values().stream()
                            .filter(ts -> ts >= last.ts()).count();
            readStatusCache.put(last.id(), count > 0 ? "已读 " + count : "已送达");
        }
    }

    /** 已读状态变化：重绘消息列表 */
    public void refreshReadStatus() {
        readStatusDirty = true;
        if (messageList.getItems() != null) {
            messageList.refresh();
        }
    }

    /** 好友资料变化后刷新头部（头像/昵称） */
    public void refreshHeader() {
        if (current != null) {
            titleLabel.setText(current.getTitle());
            avatar.setText(Avatars.initial(current.getTitle()));
            Avatars.apply(avatar, current.getPeerId(), current.avatar());
            refreshSubtitle();
        }
    }

    /** 发送者改了头像/昵称：让消息气泡重新渲染（MessageCell 会对比快照后重画） */
    public void refreshMessages() {
        if (messageList != null) {
            messageList.refresh();
        }
    }

    /** 切换会话 */
    public void open(Conversation conversation) {
        hideMentionPopup();
        fadeMessages();
        if (current != null) {
            current.messages().removeListener(messageWatcher);
            // 切换前把上一会话没发出去的草稿存起来
            ctx.store().saveDraft(current.key(), input.getText());
        }
        this.current = conversation;
        if (conversation.getChatType() == ChatType.GROUP) {
            checkGroupAnnouncement(conversation.getPeerId());
        }
        conversation.messages().addListener(messageWatcher);
        readStatusDirty = true;
        // 先用本地缓存渲染，再向服务端 sync 缺口（断网时也能看到历史）
        if (ctx.store().loadLocalMessages(conversation, 3000) > 0) {
            readStatusDirty = true;
        }
        titleLabel.setText(conversation.getTitle());
        avatar.setText(Avatars.initial(conversation.getTitle()));
        Avatars.apply(avatar, conversation.getPeerId(), conversation.avatar());
        messageList.setItems(conversation.messages());
        input.setText(ctx.store().local() == null ? ""
                : ctx.store().local().draft(conversation.key()));
        ctx.markRead(conversation);
        emptyState.setText(conversation.getChatType() == ChatType.GROUP
                ? "群里还没有消息" : "还没有消息，说点什么吧");
        refreshSubtitle();

        // 首次打开从 seq=0 拉最近 50 条；断线重连后则是“补缺口”。成功才标记 loaded
        if (!conversation.isHistoryLoaded() && !conversation.isHistoryRequested()) {
            conversation.setHistoryRequested(true);
            ctx.requestSync(conversation).whenComplete((resp, error) -> Platform.runLater(() -> {
                if (error == null && resp != null) {
                    conversation.markHistoryLoaded();
                } else {
                    conversation.setHistoryRequested(false);
                }
            }));
        }
        scrollToBottom();
    }

    /** 主界面重建/退出登录时释放监听，避免旧界面被 store 一直引用 */
    public void dispose() {
        if (current != null) {
            current.messages().removeListener(messageWatcher);
            ctx.store().saveDraft(current.key(), input.getText());
        }
        draftTimer.stop();
        hideMentionPopup();
        if (scrollAnimation != null) {
            scrollAnimation.stop();
            scrollAnimation = null;
        }
    }

    private void refreshSubtitle() {
        subtitleLabel.textProperty().unbind();
        if (current == null) {
            subtitleLabel.setText("从左侧选择好友或群开始聊天");
            return;
        }
        if (current.getChatType() == ChatType.GROUP) {
            subtitleLabel.setText("群号 " + current.getPeerId() + " · 群聊");
            return;
        }
        String peerId = current.getPeerId();
        subtitleLabel.textProperty().bind(Bindings.when(current.onlineProperty())
                .then("QC " + peerId + " · 在线")
                .otherwise("QC " + peerId + " · 离线"));
    }

    public void send() {
        if (current == null) {
            return;
        }
        String text = input.getText() == null ? "" : input.getText().trim();
        if (text.isEmpty()) {
            return;
        }
        User me = ctx.store().me();
        if (me == null) {
            return;
        }
        if (ctx.isOffline()) {
            Toast.show(this, "离线模式，无法发送消息");
            return;
        }
        if (!ctx.ws().isOpen()) {
            Toast.show(this, "连接已断开，消息未发送");
            return;
        }

        String clientMsgId = UUID.randomUUID().toString();
        ChatMessage local = new ChatMessage(
                Conversation.LOCAL_ID_PREFIX + clientMsgId,
                clientMsgId,
                me.id(),
                me.nickname(),
                current.getPeerId(),
                current.getChatType(),
                MsgType.TEXT,
                text,
                System.currentTimeMillis());

        // 乐观插入：先显示再发送
        current.messages().add(local);
        input.clear();
        ctx.sendMessage(current, clientMsgId, text);
        scrollToBottom();
    }

    /**
     * 滚动到底部。
     *
     * <p>先 scrollTo 定位，再对纵向滚动条做 120ms 的缓动，
     * 这样新消息进来是「滑」下去的，而不是瞬间跳（观感上更顺）。
     */
    /** 切换会话时消息区淡入：比"啪"一下换内容柔和很多，代价只是一个节点 120ms 的透明度动画 */
    private void fadeMessages() {
        javafx.animation.FadeTransition fade = new javafx.animation.FadeTransition(
                Duration.millis(120), messageList);
        fade.setFromValue(0.35);
        fade.setToValue(1);
        fade.play();
    }

    public void scrollToBottom() {
        if (messageList.getItems() == null || messageList.getItems().isEmpty()) {
            return;
        }
        int last = messageList.getItems().size() - 1;
        Platform.runLater(() -> {
            messageList.scrollTo(last);
            messageList.lookupAll(".scroll-bar").stream()
                    .filter(node -> node instanceof javafx.scene.control.ScrollBar bar
                            && bar.getOrientation() == javafx.geometry.Orientation.VERTICAL)
                    .findFirst()
                    .ifPresent(node -> {
                        javafx.scene.control.ScrollBar bar = (javafx.scene.control.ScrollBar) node;
                        if (bar.getValue() >= bar.getMax() - 0.001) {
                            return;
                        }
                        // 连续来消息时不要叠动画，先把上一条停掉
                        if (scrollAnimation != null) {
                            scrollAnimation.stop();
                        }
                        scrollAnimation = new javafx.animation.Timeline(new javafx.animation.KeyFrame(
                                Duration.millis(120),
                                new javafx.animation.KeyValue(bar.valueProperty(), bar.getMax())));
                        scrollAnimation.play();
                    });
        });
    }

    /** “对方正在输入…” 提示，3 秒后自动消失 */
    public void showTyping(TypingNotify notify) {
        if (current == null) {
            return;
        }
        boolean sameConversation = current.getChatType() == ChatType.PRIVATE
                ? notify.from() != null && notify.from().equals(current.getPeerId())
                : notify.to() != null && notify.to().equals(current.getPeerId());
        if (!sameConversation) {
            return;
        }
        subtitleLabel.textProperty().unbind();
        String name = notify.fromName() == null ? "对方" : notify.fromName();
        subtitleLabel.setText(name + " 正在输入…");
        typingIndicator.playFromStart();
    }

    // ------------------------------------------------------------------
    // 输入区动作：文件 / 图片 / 礼物 / 通话 / 查找 / 快捷短语
    // ------------------------------------------------------------------

    private Button actionButton(String iconName, String tooltip, Runnable action) {
        Button button = new Button();
        button.setGraphic(Icons.of(iconName, 17));
        button.getStyleClass().add("composer-action");
        // 窗口变窄时按钮不能被压成省略号
        button.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
        Tooltip tip = Popups.tooltip(tooltip);
        // 默认延迟 1 秒，太长；这里 150ms 就出来
        tip.setShowDelay(Duration.millis(150));
        tip.setHideDelay(Duration.millis(80));
        tip.setShowDuration(Duration.seconds(10));
        Tooltip.install(button, tip);
        button.setOnAction(e -> action.run());
        io.quack.client.ui.motion.Motion.pressable(button, 0.9);
        return button;
    }

    private void pickFile(boolean image) {
        if (current == null) {
            return;
        }
        FileChooser chooser = new FileChooser();
        chooser.setTitle(image ? "选择图片" : "选择文件");
        if (image) {
            chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("图片",
                    "*.png", "*.jpg", "*.jpeg", "*.gif", "*.webp", "*.bmp"));
        }
        File file = chooser.showOpenDialog(getScene() == null ? null : getScene().getWindow());
        if (file == null) {
            return;
        }
        ctx.sendAttachment(current, file.toPath(), image);
    }

    /** 选择视频并发送（客户端抽帧当封面） */
    private void pickVideo() {
        if (current == null) {
            return;
        }
        if (ctx.isOffline()) {
            Toast.show(this, "离线模式，无法发送视频");
            return;
        }
        FileChooser chooser = new FileChooser();
        chooser.setTitle("选择视频");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(
                "视频（MP4 / MOV / M4V）", "*.mp4", "*.m4v", "*.mov"));
        File file = chooser.showOpenDialog(getScene() == null ? null : getScene().getWindow());
        if (file == null) {
            return;
        }
        ctx.sendVideo(current, file.toPath());
    }

    private void openGift() {
        if (current != null) {
            GiftDialog.show(getScene() == null ? null : getScene().getWindow(), ctx, current, giftButton);
        }
    }

    private void startCall() {
        if (current == null) {
            return;
        }
        CallDialog.outgoing(getScene() == null ? null : getScene().getWindow(), ctx,
                current.getPeerId(), current.getTitle(),
                current.getChatType() == ChatType.GROUP);
    }

    private void openSearch() {
        if (current == null) {
            return;
        }
        Conversation conversation = current;
        javafx.stage.Window owner = getScene() == null ? null : getScene().getWindow();
        if (conversation.getChatType() == ChatType.GROUP) {
            ctx.loadGroupDetail(conversation.getPeerId()).whenComplete((detail, error) ->
                    Platform.runLater(() -> ChatSearchDialog.show(owner, ctx.store(), conversation,
                            detail == null ? null : detail.members(), this::locateMessage, searchButton)));
        } else {
            ChatSearchDialog.show(owner, ctx.store(), conversation, null,
                    this::locateMessage, searchButton);
        }
    }

    /** 从聊天记录查找结果定位到消息列表里的位置 */
    public void locateMessage(ChatMessage target) {
        if (current == null || target == null) {
            return;
        }
        int index = indexOfMessage(target);
        if (index < 0) {
            ctx.store().loadLocalMessages(current, 3000);
            index = indexOfMessage(target);
        }
        if (index < 0) {
            Toast.show(this, "这条消息不在本机缓存里");
            return;
        }
        messageList.getSelectionModel().select(index);
        messageList.scrollTo(Math.max(0, index - 2));
        messageList.requestFocus();
        Toast.show(this, "已定位到这条消息");
    }

    private int indexOfMessage(ChatMessage target) {
        if (current == null || target == null) {
            return -1;
        }
        for (int i = current.messages().size() - 1; i >= 0; i--) {
            ChatMessage message = current.messages().get(i);
            if (message.id() != null && message.id().equals(target.id())) {
                return i;
            }
        }
        return current.messages().indexOf(target);
    }

    /** 进群时检查群公告：有没“收到”过的公告就弹窗（多条一起展示） */
    private void checkGroupAnnouncement(String groupId) {
        if (groupId == null || !announcementOpen.add(groupId)) {
            return;
        }
        ctx.loadGroupDetail(groupId).whenComplete((detail, error) -> Platform.runLater(() -> {
            if (error != null || detail == null || ctx.store().local() == null) {
                announcementOpen.remove(groupId);
                return;
            }
            java.util.List<io.quack.common.model.GroupAnnouncement> all =
                    new java.util.ArrayList<>(detail.announcementList());
            if (all.isEmpty() && detail.announcement() != null && !detail.announcement().isBlank()) {
                all.add(new io.quack.common.model.GroupAnnouncement("legacy", detail.announcement(),
                        detail.ownerId(), "群主", 0));
            }
            java.util.List<io.quack.common.model.GroupAnnouncement> unseen = new java.util.ArrayList<>();
            for (io.quack.common.model.GroupAnnouncement item : all) {
                String hash = io.quack.client.store.LocalStore.hashAnnouncement(
                        item.id() + "|" + item.text());
                if (!ctx.store().isAnnouncementHashSeen(groupId, hash)) {
                    unseen.add(item);
                }
            }
            if (unseen.isEmpty()) {
                announcementOpen.remove(groupId);
                return;
            }
            ModalShell dialog = GroupAnnouncementDialog.show(
                    getScene() == null ? null : getScene().getWindow(),
                    detail.name(), unseen, () -> {
                        for (io.quack.common.model.GroupAnnouncement item : unseen) {
                            ctx.store().markAnnouncementHashSeen(groupId,
                                    io.quack.client.store.LocalStore.hashAnnouncement(
                                            item.id() + "|" + item.text()));
                        }
                    });
            dialog.stage().setOnHidden(e -> announcementOpen.remove(groupId));
            if (!dialog.stage().isShowing()) {
                announcementOpen.remove(groupId);
            }
        }));
    }

    private void openQuickPhrases() {
        QuickPhraseDialog.show(getScene() == null ? null : getScene().getWindow(), this::insertText, phraseButton);
    }

    /** 把文字插入输入框（快捷短语用） */
    public void insertText(String text) {
        if (text == null) {
            return;
        }
        String existing = input.getText() == null ? "" : input.getText();
        input.setText(existing.isEmpty() ? text : existing + text);
        input.positionCaret(input.getText().length());
        input.requestFocus();
    }

    /** 消息右键菜单：撤回 / 复制 / 设为精华 */
    private ContextMenu messageMenu(ChatMessage message) {
        ContextMenu menu = new ContextMenu();
        Popups.install(menu);

        User me = ctx.store().me();
        boolean mine = me != null && me.id().equals(message.from());
        String id = message.id();
        boolean recallable = mine && id != null
                && !id.startsWith(Conversation.LOCAL_ID_PREFIX)
                && !id.startsWith(Conversation.FAILED_ID_PREFIX)
                && message.msgType() != MsgType.SYSTEM
                && System.currentTimeMillis() - message.ts() <= 2 * 60 * 1000;
        boolean editable = mine && id != null
                && !id.startsWith(Conversation.LOCAL_ID_PREFIX)
                && !id.startsWith(Conversation.FAILED_ID_PREFIX)
                && message.msgType() == MsgType.TEXT;
        if (editable) {
            MenuItem edit = new MenuItem("编辑");
            edit.setOnAction(e -> openEditor(message));
            menu.getItems().add(edit);
        }
        if (recallable) {
            MenuItem recall = new MenuItem("撤回");
            recall.setOnAction(e -> ctx.recallMessage(id)
                    .whenComplete((recalled, error) -> Platform.runLater(() -> {
                        if (error != null) {
                            Toast.show(this, "撤回失败：" + ClientErrors.describe(error));
                        }
                    })));
            menu.getItems().add(recall);
        }

        MenuItem copy = new MenuItem("复制文本");
        copy.setOnAction(e -> {
            ClipboardContent content = new ClipboardContent();
            content.putString(message.content());
            Clipboard.getSystemClipboard().setContent(content);
        });
        menu.getItems().add(copy);

        if (current != null && current.getChatType() == ChatType.GROUP
                && message.id() != null && !message.id().startsWith(Conversation.LOCAL_ID_PREFIX)) {
            MenuItem essence = new MenuItem("设为精华");
            essence.setOnAction(e -> ctx.addEssence(current.getPeerId(), message.id())
                    .whenComplete((list, error) -> Platform.runLater(() -> {
                        if (error != null) {
                            Toast.show(this, "操作失败：" + ClientErrors.describe(error));
                        } else {
                            Toast.show(this, "已设为精华消息");
                        }
                    })));
            menu.getItems().add(essence);
        }
        return menu;
    }

    /** 编辑自己的文本消息 */
    private void openEditor(ChatMessage message) {
        TextInputDialog.show(getScene() == null ? null : getScene().getWindow(),
                "编辑消息", "只能编辑自己发送的文本消息", message.content(),
                text -> {
                    if (text == null || text.isBlank() || text.equals(message.content())) {
                        return;
                    }
                    ctx.editMessage(message.id(), text)
                            .whenComplete((edited, error) -> Platform.runLater(() -> {
                                if (error != null) {
                                    Toast.show(this, "编辑失败：" + ClientErrors.describe(error));
                                }
                            }));
                });
    }

    public void setOnOpenInfo(Runnable onOpenInfo) {
        this.onOpenInfo = onOpenInfo == null ? () -> {
        } : onOpenInfo;
    }

    public Conversation current() {
        return current;
    }
}
