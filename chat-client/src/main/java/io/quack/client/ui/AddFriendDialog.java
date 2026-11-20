package io.quack.client.ui;

import io.quack.client.AppContext;
import io.quack.client.ClientErrors;
import io.quack.common.model.ChatType;
import io.quack.common.model.User;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.util.List;

/**
 * 添加好友：按 QC 号或用户名搜索；点「加好友」后再弹确认窗口填写备注。
 */
public final class AddFriendDialog {

    private AddFriendDialog() {
    }

    public static void show(Window owner, AppContext ctx) {
        ModalShell shell = new ModalShell(owner, "添加好友", 560, 560);
        shell.setContent(new Content(ctx, shell));
        shell.showAndWait();
    }

    private static final class Content extends VBox {

        private final AppContext ctx;
        private final ModalShell shell;
        private final TextField searchField = new TextField();
        private final Label statusLabel = new Label("输入 QC 号或用户名后点搜索");
        private final ObservableList<User> results = FXCollections.observableArrayList();
        private final ListView<User> resultList = new ListView<>(results);

        Content(AppContext ctx, ModalShell shell) {
            this.ctx = ctx;
            this.shell = shell;
            setSpacing(12);
            setPadding(new Insets(18));
            getStyleClass().add("modal-body");

            Label hint = new Label("对方的 QC 号是唯一标识；用户名可能重名，搜索结果按 QC 号排序");
            hint.getStyleClass().add("modal-hint");
            hint.setWrapText(true);

            searchField.setPromptText("QC 号（如 10002）或用户名");
            searchField.getStyleClass().add("search-field");
            HBox.setHgrow(searchField, Priority.ALWAYS);
            searchField.setOnAction(e -> doSearch());

            Button searchButton = new Button("搜索");
            searchButton.getStyleClass().add("primary-button");
            searchButton.setOnAction(e -> doSearch());

            HBox searchRow = new HBox(8, searchField, searchButton);
            searchRow.setAlignment(Pos.CENTER_LEFT);

            Label tip = new Label("点「加好友」后会弹出确认窗口，可在那里填写备注");
            tip.getStyleClass().add("modal-hint");

            statusLabel.getStyleClass().add("login-status");

            resultList.getStyleClass().add("conversation-list");
            resultList.setCellFactory(view -> new ResultCell());
            resultList.setPlaceholder(new Label("输入关键字后点搜索"));
            VBox.setVgrow(resultList, Priority.ALWAYS);

            getChildren().addAll(hint, searchRow, tip, statusLabel, resultList);
            Platform.runLater(searchField::requestFocus);
        }

        private void doSearch() {
            String keyword = searchField.getText() == null ? "" : searchField.getText().trim();
            if (keyword.isEmpty()) {
                statusLabel.setText("请输入 QC 号或用户名");
                return;
            }
            statusLabel.setText("搜索中…");
            ctx.searchUsers(keyword).whenComplete((users, error) -> Platform.runLater(() -> {
                if (error != null) {
                    statusLabel.setText("搜索失败：" + ClientErrors.describe(error));
                    return;
                }
                List<User> found = users == null ? List.of() : users;
                results.setAll(found);
                statusLabel.setText(found.isEmpty() ? "没有找到匹配的用户" : "找到 " + found.size() + " 个用户");
            }));
        }

        /** 结果行：头像 + 昵称/用户名 + QC 号 + 加好友 */
        private final class ResultCell extends ListCell<User> {

            @Override
            protected void updateItem(User user, boolean empty) {
                super.updateItem(user, empty);
                if (empty || user == null) {
                    setGraphic(null);
                    return;
                }

                Label avatar = new Label(Avatars.initial(user.displayName()));
                avatar.getStyleClass().add("avatar");
                avatar.setMinSize(40, 40);
                avatar.setPrefSize(40, 40);
                avatar.setMaxSize(40, 40);
                Avatars.apply(avatar, user.id(), user.avatar());

                Label name = new Label(user.displayName());
                name.getStyleClass().add("conv-title");
                Label meta = new Label("QC " + user.qcId() + " · @" + user.username());
                meta.getStyleClass().add("conv-preview");
                VBox texts = new VBox(2, name, meta);
                HBox.setHgrow(texts, Priority.ALWAYS);

                boolean self = ctx.me() != null && ctx.me().id().equals(user.id());
                boolean friend = ctx.store().find(ChatType.PRIVATE, user.id()) != null;
                Button addButton = new Button();
                addButton.getStyleClass().add("small-primary-button");
                if (self) {
                    addButton.setText("这是你自己");
                    addButton.setDisable(true);
                } else if (friend) {
                    addButton.setText("已是好友");
                    addButton.setDisable(true);
                } else {
                    addButton.setText("加好友");
                    addButton.setOnAction(e -> openConfirm(user, addButton));
                }

                HBox row = new HBox(12, avatar, texts, addButton);
                row.setAlignment(Pos.CENTER_LEFT);
                row.setPadding(new Insets(6, 8, 6, 8));
                setGraphic(row);
            }
        }

        /** 点加好友后的确认窗口：这里才填备注 */
        private void openConfirm(User user, Button sourceButton) {
            ModalShell dialog = new ModalShell(shell.stage(), "确认添加好友", 480, 340);

            Label avatar = new Label(Avatars.initial(user.displayName()));
            avatar.getStyleClass().add("avatar");
            avatar.setMinSize(48, 48);
            avatar.setPrefSize(48, 48);
            avatar.setMaxSize(48, 48);
            Avatars.apply(avatar, user.id(), user.avatar());
            Label name = new Label(user.displayName());
            name.getStyleClass().add("profile-name");
            Label meta = new Label("QC " + user.qcId() + " · @" + user.username());
            meta.getStyleClass().add("profile-sub");
            VBox who = new VBox(2, name, meta);
            HBox target = new HBox(12, avatar, who);
            target.setAlignment(Pos.CENTER_LEFT);

            Label remarkLabel = new Label("备注（昵称）");
            remarkLabel.getStyleClass().add("field-label");
            TextField remark = new TextField();
            remark.setPromptText("留空则显示对方用户名/昵称");
            remark.getStyleClass().add("search-field");

            Label error = new Label();
            error.getStyleClass().add("error");
            error.setWrapText(true);

            Button cancel = new Button("取消");
            cancel.getStyleClass().add("secondary-button");
            cancel.setOnAction(e -> dialog.close());
            Button send = new Button("发送申请");
            send.getStyleClass().add("primary-button");
            send.setOnAction(e -> {
                send.setDisable(true);
                String text = remark.getText() == null ? "" : remark.getText().trim();
                ctx.sendFriendRequest(user.id(),
                                "我是 " + (ctx.me() == null ? "" : ctx.me().displayName()),
                                text.isBlank() ? null : text)
                        .whenComplete((request, ex) -> Platform.runLater(() -> {
                            if (ex != null) {
                                send.setDisable(false);
                                error.setText("申请失败：" + ClientErrors.describe(ex));
                                return;
                            }
                            sourceButton.setText("已发送");
                            sourceButton.setDisable(true);
                            statusLabel.setText("申请已发送，等待对方在「新的朋友」里同意");
                            dialog.close();
                        }));
            });
            Region spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);
            HBox actions = new HBox(8, spacer, cancel, send);
            actions.setAlignment(Pos.CENTER_RIGHT);

            VBox body = new VBox(12, target, remarkLabel, remark, error, actions);
            body.setPadding(new Insets(16));
            body.getStyleClass().add("modal-body");
            dialog.setContent(body);
            dialog.showAndWait();
            Platform.runLater(remark::requestFocus);
        }
    }
}
