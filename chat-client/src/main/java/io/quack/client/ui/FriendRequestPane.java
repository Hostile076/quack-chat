package io.quack.client.ui;

import io.quack.client.AppContext;
import io.quack.client.ClientErrors;
import io.quack.common.model.FriendRequest;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * 「新的朋友」页面：管理好友申请。
 *
 * <p>上半部分是别人发给我的申请（同意 / 拒绝），
 * 下半部分是我发出去、还在等对方处理的申请。
 */
public class FriendRequestPane extends BorderPane {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("MM-dd HH:mm")
            .withZone(ZoneId.systemDefault());

    private final AppContext ctx;
    private final ListView<FriendRequest> incomingList = new ListView<>();
    private final ListView<FriendRequest> outgoingList = new ListView<>();
    private final Label emptyHint = new Label("暂时没有新的好友申请");

    public FriendRequestPane(AppContext ctx) {
        this.ctx = ctx;
        getStyleClass().add("chat-pane");

        setTop(buildHeader());
        setCenter(buildBody());
        refresh();
    }

    private HBox buildHeader() {
        Label title = new Label("新的朋友");
        title.getStyleClass().add("chat-title");
        Label subtitle = new Label("对方同意后才会成为好友");
        subtitle.getStyleClass().add("chat-subtitle");
        VBox texts = new VBox(2, title, subtitle);

        Button refresh = new Button("刷新");
        refresh.getStyleClass().add("secondary-button");
        refresh.setOnAction(e -> {
            refresh.setDisable(true);
            ctx.loadFriendRequests().whenComplete((resp, error) ->
                    Platform.runLater(() -> refresh.setDisable(false)));
        });

        Button addFriend = new Button("＋ 添加好友");
        addFriend.getStyleClass().add("primary-button");
        addFriend.setOnAction(e -> AddFriendDialog.show(getScene() == null ? null : getScene().getWindow(), ctx));

        javafx.scene.layout.Region spacer = new javafx.scene.layout.Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox header = new HBox(10, texts, spacer, refresh, addFriend);
        header.setAlignment(Pos.CENTER_LEFT);
        header.getStyleClass().add("chat-header");
        header.setPadding(new Insets(14, 18, 14, 18));
        return header;
    }

    private VBox buildBody() {
        Label incomingTitle = new Label("收到的申请");
        incomingTitle.getStyleClass().add("section-title");

        incomingList.setItems(ctx.store().incomingRequests());
        incomingList.getStyleClass().add("message-list");
        incomingList.setCellFactory(view -> new IncomingCell());
        incomingList.setPlaceholder(emptyHint);
        emptyHint.getStyleClass().add("empty-state");
        VBox.setVgrow(incomingList, Priority.ALWAYS);

        Label outgoingTitle = new Label("我发出的申请");
        outgoingTitle.getStyleClass().add("section-title");

        outgoingList.setItems(ctx.store().outgoingRequests());
        outgoingList.getStyleClass().add("message-list");
        outgoingList.setCellFactory(view -> new OutgoingCell());
        outgoingList.setPlaceholder(new Label("没有等待验证的申请"));
        outgoingList.setPrefHeight(150);
        outgoingList.setMaxHeight(180);

        VBox body = new VBox(8, incomingTitle, incomingList, outgoingTitle, outgoingList);
        body.setPadding(new Insets(14, 18, 16, 18));
        body.getStyleClass().add("requests-body");
        return body;
    }

    /** 数据变化后刷新空态文案（列表本身绑定的是同一个 ObservableList，会自动更新） */
    public void refresh() {
        emptyHint.setText("暂时没有新的好友申请，点右上角「添加好友」去找人吧");
    }

    private final class IncomingCell extends ListCell<FriendRequest> {

        @Override
        protected void updateItem(FriendRequest request, boolean empty) {
            super.updateItem(request, empty);
            if (empty || request == null) {
                setGraphic(null);
                return;
            }

            Label avatar = new Label(Avatars.initial(request.fromName()));
            avatar.getStyleClass().add("avatar");
            avatar.setMinSize(42, 42);
            avatar.setPrefSize(42, 42);
            avatar.setMaxSize(42, 42);
            Avatars.apply(avatar, request.fromId(), request.fromAvatar());

            Label name = new Label(request.fromName() + "（" + request.fromId() + "）");
            name.getStyleClass().add("conv-title");
            Label message = new Label(request.message() == null || request.message().isBlank()
                    ? "请求添加你为好友" : request.message());
            message.getStyleClass().add("conv-preview");
            message.setWrapText(true);
            Label time = new Label(TIME.format(Instant.ofEpochMilli(request.ts())));
            time.getStyleClass().add("conv-time");

            VBox texts = new VBox(3, name, message, time);
            HBox.setHgrow(texts, Priority.ALWAYS);

            Button accept = new Button("同意");
            accept.getStyleClass().add("primary-button");
            Button reject = new Button("拒绝");
            reject.getStyleClass().add("secondary-button");

            accept.setOnAction(e -> handle(request, true, accept, reject));
            reject.setOnAction(e -> handle(request, false, accept, reject));

            HBox actions = new HBox(8, accept, reject);
            actions.setAlignment(Pos.CENTER_RIGHT);

            HBox row = new HBox(12, avatar, texts, actions);
            row.setAlignment(Pos.CENTER_LEFT);
            row.setPadding(new Insets(8, 8, 8, 8));
            row.getStyleClass().add("request-row");
            setGraphic(row);
        }

        private void handle(FriendRequest request, boolean accept, Button acceptButton, Button rejectButton) {
            acceptButton.setDisable(true);
            rejectButton.setDisable(true);
            ctx.handleFriendRequest(request.id(), accept).whenComplete((resp, error) -> Platform.runLater(() -> {
                if (error != null) {
                    acceptButton.setDisable(false);
                    rejectButton.setDisable(false);
                    acceptButton.setTooltip(Popups.tooltip(ClientErrors.describe(error)));
                }
            }));
        }
    }

    private static final class OutgoingCell extends ListCell<FriendRequest> {

        @Override
        protected void updateItem(FriendRequest request, boolean empty) {
            super.updateItem(request, empty);
            if (empty || request == null) {
                setGraphic(null);
                return;
            }
            Label text = new Label("已向 " + request.toId() + " 发送申请，等待对方验证");
            text.getStyleClass().add("conv-preview");
            Label time = new Label(TIME.format(Instant.ofEpochMilli(request.ts())));
            time.getStyleClass().add("conv-time");

            VBox texts = new VBox(2, text, time);
            HBox row = new HBox(8, texts);
            row.setPadding(new Insets(6, 8, 6, 8));
            setGraphic(row);
        }
    }
}
