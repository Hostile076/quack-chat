package io.quack.client.ui;

import io.quack.client.AppContext;
import io.quack.client.ClientErrors;
import io.quack.client.store.Conversation;
import io.quack.common.model.ChatType;
import io.quack.common.model.GroupDetail;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * 创建群聊：填群名 + 勾选要拉进来的好友。
 *
 * <p>创建成功后服务端把我设为群主，并给所有成员推 {@code group.updated.push}；
 * 本机直接打开新群的会话。
 */
public final class CreateGroupDialog {

    private CreateGroupDialog() {
    }

    public static void show(Window owner, AppContext ctx, Consumer<GroupDetail> onCreated) {
        ModalShell shell = new ModalShell(owner, "创建群聊", 460, 600);
        List<Conversation> friends = ctx.store().conversations().stream()
                .filter(conversation -> conversation.getChatType() == ChatType.PRIVATE)
                .toList();
        Set<String> selected = new LinkedHashSet<>();

        VBox content = new VBox(14);
        content.setPadding(new Insets(20));
        content.getStyleClass().add("modal-body");

        Label title = new Label("创建群聊");
        title.getStyleClass().add("profile-name");

        TextField nameField = new TextField();
        nameField.setPromptText("给群聊起个名字");
        nameField.getStyleClass().add("login-field");
        nameField.setMaxWidth(Double.MAX_VALUE);

        Label membersLabel = new Label("选择要拉进群的好友（" + friends.size() + "）");
        membersLabel.getStyleClass().add("field-label");

        VBox rows = new VBox(4);
        Label selectedLabel = new Label();
        selectedLabel.getStyleClass().add("modal-hint");
        Runnable refreshSelected = () -> selectedLabel.setText(
                selected.isEmpty() ? "还没有选择好友" : "已选择 " + selected.size() + " 位好友");
        // 勾选好友也要重新算「创建」按钮的可用状态：只监听群名会漏掉“先填名后勾人”
        Runnable[] refreshButton = new Runnable[1];

        for (Conversation friend : friends) {
            rows.getChildren().add(friendRow(friend, selected, () -> {
                refreshSelected.run();
                if (refreshButton[0] != null) {
                    refreshButton[0].run();
                }
            }));
        }
        if (friends.isEmpty()) {
            Label empty = new Label("还没有好友，先去添加好友吧");
            empty.getStyleClass().add("empty-state");
            empty.setPadding(new Insets(16));
            rows.getChildren().add(empty);
        }

        ScrollPane scroll = new ScrollPane(rows);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("edge-to-edge");
        scroll.setPrefHeight(300);
        VBox.setVgrow(scroll, Priority.ALWAYS);

        Label hint = new Label("群主是你；建好后可以在群资料里换群头像、发公告");
        hint.getStyleClass().add("modal-hint");
        hint.setWrapText(true);

        Button cancel = new Button("取消");
        cancel.getStyleClass().add("secondary-button");
        cancel.setOnAction(e -> shell.close());

        Button create = new Button("创建群聊");
        create.getStyleClass().add("primary-button");
        create.setDefaultButton(true);
        io.quack.client.ui.motion.Motion.pressable(create, 0.97);

        Label error = new Label();
        error.getStyleClass().add("error");
        error.setWrapText(true);

        refreshButton[0] = () -> create.setDisable(
                nameField.getText() == null || nameField.getText().isBlank() || selected.isEmpty());
        nameField.textProperty().addListener((obs, old, value) -> refreshButton[0].run());
        refreshSelected.run();
        refreshButton[0].run();

        create.setOnAction(e -> {
            String name = nameField.getText() == null ? "" : nameField.getText().trim();
            if (name.isEmpty()) {
                error.setText("请填写群名称");
                return;
            }
            if (selected.isEmpty()) {
                error.setText("至少选择一个好友");
                return;
            }
            create.setDisable(true);
            create.setText("创建中…");
            ctx.createGroup(name, new ArrayList<>(selected)).whenComplete((detail, failure) ->
                    Platform.runLater(() -> {
                        create.setDisable(false);
                        create.setText("创建群聊");
                        if (failure != null) {
                            error.setText("创建失败：" + ClientErrors.describe(failure));
                            refreshButton[0].run();
                            return;
                        }
                        Window toastOwner = shell.stage().getOwner();
                        shell.close();
                        if (onCreated != null) {
                            onCreated.accept(detail);
                        }
                        Toast.showAboveWindow(toastOwner, "群聊「" + detail.name() + "」已创建", true);
                    }));
        });

        nameField.setOnAction(e -> {
            if (!create.isDisable()) {
                create.fire();
            }
        });

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox actions = new HBox(10, error, spacer, cancel, create);
        actions.setAlignment(Pos.CENTER_LEFT);

        content.getChildren().addAll(title,
                new VBox(6, new Label("群名称") {{
                    getStyleClass().add("field-label");
                }}, nameField),
                membersLabel, scroll, selectedLabel, hint, actions);
        VBox.setVgrow(content, Priority.ALWAYS);
        shell.setContent(content);
        Platform.runLater(nameField::requestFocus);
        shell.showAndWait();
    }

    private static HBox friendRow(Conversation friend, Set<String> selected, Runnable onChange) {
        Label avatar = new Label(Avatars.initial(friend.getTitle()));
        avatar.getStyleClass().add("avatar");
        avatar.setMinSize(36, 36);
        avatar.setPrefSize(36, 36);
        avatar.setMaxSize(36, 36);
        Avatars.apply(avatar, friend.getPeerId(), friend.avatar());

        CheckBox check = new CheckBox();
        check.setMinWidth(Region.USE_PREF_SIZE);
        check.selectedProperty().addListener((obs, old, value) -> {
            if (value) {
                selected.add(friend.getPeerId());
            } else {
                selected.remove(friend.getPeerId());
            }
            onChange.run();
        });

        Label name = new Label(friend.getTitle());
        name.getStyleClass().add("option-title");
        Label meta = new Label("QC " + friend.getPeerId());
        meta.getStyleClass().add("option-value");
        VBox texts = new VBox(1, name, meta);
        HBox.setHgrow(texts, Priority.ALWAYS);

        HBox row = new HBox(10, check, avatar, texts);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("option-row");
        row.setPadding(new Insets(7, 10, 7, 10));
        row.setCursor(javafx.scene.Cursor.HAND);
        row.setOnMouseClicked(e -> {
            if (e.getTarget() == check) {
                return;
            }
            check.setSelected(!check.isSelected());
        });
        return row;
    }
}
