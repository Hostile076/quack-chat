package io.quack.client.ui;

import io.quack.client.AppContext;
import io.quack.client.store.Conversation;
import io.quack.common.model.ChatType;
import javafx.collections.ListChangeListener;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 通讯录：最上面是「新的朋友」（加好友入口），下面按好友 / 群聊分组。
 *
 * <p>和「聊天」页的区别：聊天页只列会话（按最近消息），通讯录列出**全部**联系人和群，
 * 不管有没有聊过。
 */
public class ContactsPane extends VBox {

    private final AppContext ctx;
    private final Consumer<Conversation> onOpenConversation;
    private final Runnable onOpenRequests;
    private final TextField search = new TextField();
    private final VBox list = new VBox(2);
    private final Label requestBadge = new Label();
    /** store 比面板活得久，监听必须能摘掉 */
    private final ListChangeListener<Conversation> conversationWatcher;
    private final ListChangeListener<io.quack.common.model.FriendRequest> requestWatcher;

    public ContactsPane(AppContext ctx, Consumer<Conversation> onOpenConversation, Runnable onOpenRequests) {
        this.ctx = ctx;
        this.onOpenConversation = onOpenConversation;
        this.onOpenRequests = onOpenRequests;
        // 每次 rebuild 都会重建行，但样式类只能加一次，否则样式表里会堆无数个同名 class
        requestBadge.getStyleClass().add("unread-badge");

        getStyleClass().add("sidebar");
        setSpacing(10);
        setPadding(new Insets(16, 10, 10, 10));

        Label title = new Label("通讯录");
        title.getStyleClass().add("sidebar-title");

        search.setPromptText("搜索好友 / 群");
        search.getStyleClass().add("search-field");
        search.textProperty().addListener((obs, old, text) -> rebuild());

        ScrollPane scroll = new ScrollPane(list);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("contacts-scroll");
        VBox.setVgrow(scroll, Priority.ALWAYS);

        getChildren().addAll(title, search, scroll);

        conversationWatcher = change -> rebuild();
        requestWatcher = change -> {
            updateBadge();
            rebuild();
        };
        ctx.store().conversations().addListener(conversationWatcher);
        ctx.store().incomingRequests().addListener(requestWatcher);
        rebuild();
    }

    /** 主界面重建/退出登录时调用 */
    public void dispose() {
        ctx.store().conversations().removeListener(conversationWatcher);
        ctx.store().incomingRequests().removeListener(requestWatcher);
    }

    private void updateBadge() {
        int count = ctx.store().pendingRequestCount();
        requestBadge.setText(String.valueOf(count));
        requestBadge.setVisible(count > 0);
        requestBadge.setManaged(count > 0);
    }

    public void rebuild() {
        String keyword = search.getText() == null ? "" : search.getText().trim().toLowerCase();
        list.getChildren().setAll(newCreateGroupEntry(), newFriendEntry(), newGroupEntry(), newPendingEntry());
        List<Conversation> friends = new ArrayList<>();
        List<Conversation> groups = new ArrayList<>();
        for (Conversation conversation : ctx.store().conversations()) {
            boolean matches = keyword.isEmpty() || conversation.getTitle().toLowerCase().contains(keyword);
            if (!matches) {
                continue;
            }
            if (conversation.getChatType() == ChatType.PRIVATE) {
                friends.add(conversation);
            } else {
                groups.add(conversation);
            }
        }

        if (!friends.isEmpty()) {
            list.getChildren().add(sectionTitle("好友 · " + friends.size()));
            friends.forEach(friend -> list.getChildren().add(contactRow(friend, true)));
        }
        if (!groups.isEmpty()) {
            list.getChildren().add(sectionTitle("群聊 · " + groups.size()));
            groups.forEach(group -> list.getChildren().add(contactRow(group, false)));
        }
        if (friends.isEmpty() && groups.isEmpty()) {
            Label empty = new Label("没有匹配的联系人");
            empty.getStyleClass().add("empty-state");
            empty.setPadding(new Insets(20));
            list.getChildren().add(empty);
        }
    }

    /** 「创建群聊」入口：勾选好友建一个新群 */
    private HBox newCreateGroupEntry() {
        Label icon = new Label();
        icon.setGraphic(Icons.of("plus", 19));
        Label text = new Label("创建群聊");
        text.getStyleClass().add("entry-title");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Button create = new Button("＋");
        create.getStyleClass().add("add-friend-button");
        create.setTooltip(Popups.tooltip("创建群聊"));
        create.setOnAction(e -> openCreateGroup());

        HBox row = new HBox(10, icon, text, spacer, create);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("contact-row");
        row.setPadding(new Insets(8, 10, 8, 10));
        row.setOnMouseClicked(e -> openCreateGroup());
        return row;
    }

    private void openCreateGroup() {
        CreateGroupDialog.show(getScene() == null ? null : getScene().getWindow(), ctx, detail -> {
            Conversation conversation = ctx.store().find(ChatType.GROUP, detail.id());
            if (conversation != null && onOpenConversation != null) {
                onOpenConversation.accept(conversation);
            }
        });
    }
    /** 「加群」入口：和加好友同一套搜索逻辑 */
    private HBox newGroupEntry() {
        Label icon = new Label();
        icon.setGraphic(Icons.of("contacts", 19));
        Label text = new Label("加群");
        text.getStyleClass().add("entry-title");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Button add = new Button("＋");
        add.getStyleClass().add("add-friend-button");
        add.setTooltip(Popups.tooltip("加群"));
        add.setOnAction(e -> AddGroupDialog.show(getScene() == null ? null : getScene().getWindow(), ctx));

        HBox row = new HBox(10, icon, text, spacer, add);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("contact-row");
        row.setPadding(new Insets(8, 10, 8, 10));
        row.setOnMouseClicked(e -> AddGroupDialog.show(getScene() == null ? null : getScene().getWindow(), ctx));
        return row;
    }

    /** 「待处理」入口：入群申请列表（我发出的 + 需要我审批的） */
    private HBox newPendingEntry() {
        Label icon = new Label();
        icon.setGraphic(Icons.of("download", 19));
        Label text = new Label("待处理");
        text.getStyleClass().add("entry-title");

        Label badge = new Label();
        badge.getStyleClass().add("unread-badge");
        badge.textProperty().bind(ctx.pendingGroupRequestsProperty().asString());
        badge.visibleProperty().bind(ctx.pendingGroupRequestsProperty().greaterThan(0));
        badge.managedProperty().bind(badge.visibleProperty());

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox row = new HBox(10, icon, text, spacer, badge);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("contact-row");
        row.setPadding(new Insets(8, 10, 8, 10));
        row.setOnMouseClicked(e -> GroupRequestsDialog.show(
                getScene() == null ? null : getScene().getWindow(), ctx));
        return row;
    }

    private HBox newFriendEntry() {
        Label icon = new Label();
        icon.setGraphic(Icons.of("wave", 19));
        Label text = new Label("新的朋友");
        text.getStyleClass().add("entry-title");

        updateBadge();

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Button add = new Button("＋");
        add.getStyleClass().add("add-friend-button");
        add.setTooltip(Popups.tooltip("添加好友"));
        add.setOnAction(e -> AddFriendDialog.show(getScene() == null ? null : getScene().getWindow(), ctx));

        HBox row = new HBox(10, icon, text, spacer, requestBadge, add);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("contact-row");
        row.setPadding(new Insets(8, 10, 8, 10));
        row.setOnMouseClicked(e -> {
            if (onOpenRequests != null) {
                onOpenRequests.run();
            }
        });
        return row;
    }

    private Label sectionTitle(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("section-title");
        label.setPadding(new Insets(10, 6, 4, 6));
        return label;
    }

    private HBox contactRow(Conversation conversation, boolean friend) {
        Label avatar = new Label(Avatars.initial(conversation.getTitle()));
        avatar.getStyleClass().add("avatar");
        avatar.setMinSize(34, 34);
        avatar.setPrefSize(34, 34);
        avatar.setMaxSize(34, 34);
        Avatars.apply(avatar, conversation.getPeerId(), conversation.avatar());

        Label name = new Label(conversation.getTitle());
        name.getStyleClass().add("conv-title");
        Label meta = new Label(friend ? "QC " + conversation.getPeerId() : "群号 " + conversation.getPeerId());
        meta.getStyleClass().add("conv-preview");

        VBox texts = new VBox(1, name, meta);
        HBox.setHgrow(texts, Priority.ALWAYS);
        texts.setMinWidth(0);

        Label dot = new Label("●");
        dot.getStyleClass().add("presence-dot");
        if (friend) {
            dot.visibleProperty().bind(conversation.onlineProperty());
        } else {
            dot.setVisible(false);
            dot.setManaged(false);
        }

        HBox row = new HBox(10, avatar, texts, dot);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("contact-row");
        row.setPadding(new Insets(7, 10, 7, 10));
        row.setOnMouseClicked(e -> {
            if (onOpenConversation != null) {
                onOpenConversation.accept(conversation);
            }
        });
        return row;
    }
}
