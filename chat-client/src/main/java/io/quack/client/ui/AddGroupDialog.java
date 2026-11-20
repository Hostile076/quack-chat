package io.quack.client.ui;

import io.quack.client.AppContext;
import io.quack.client.ClientErrors;
import io.quack.common.model.ChatGroup;
import io.quack.common.model.ChatType;
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
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.util.List;

/** 加群：按群号或群名称搜索，结果里直接加入 */
public final class AddGroupDialog {

    private AddGroupDialog() {
    }

    public static void show(Window owner, AppContext ctx) {
        ModalShell shell = new ModalShell(owner, "加群", 560, 560);
        shell.setContent(new Content(ctx, shell));
        shell.showAndWait();
    }

    private static final class Content extends VBox {

        private final AppContext ctx;
        private final ModalShell shell;
        private final TextField searchField = new TextField();
        private final Label statusLabel = new Label("输入群号或群名称后点搜索");
        private final ObservableList<ChatGroup> results = FXCollections.observableArrayList();
        private final ListView<ChatGroup> resultList = new ListView<>(results);

        Content(AppContext ctx, ModalShell shell) {
            this.ctx = ctx;
            this.shell = shell;
            setSpacing(12);
            setPadding(new Insets(18));
            getStyleClass().add("modal-body");

            Label hint = new Label("群号是唯一标识（如 q10001），也可以按群名称搜索");
            hint.getStyleClass().add("modal-hint");
            hint.setWrapText(true);

            searchField.setPromptText("群号（如 q10001）或群名称");
            searchField.getStyleClass().add("search-field");
            HBox.setHgrow(searchField, Priority.ALWAYS);
            searchField.setOnAction(e -> doSearch());

            Button searchButton = new Button("搜索");
            searchButton.getStyleClass().add("primary-button");
            searchButton.setOnAction(e -> doSearch());

            HBox searchRow = new HBox(8, searchField, searchButton);
            searchRow.setAlignment(Pos.CENTER_LEFT);

            statusLabel.getStyleClass().add("login-status");
            resultList.getStyleClass().add("conversation-list");
            resultList.setCellFactory(view -> new ResultCell());
            resultList.setPlaceholder(new Label("输入关键字后点搜索"));
            VBox.setVgrow(resultList, Priority.ALWAYS);

            getChildren().addAll(hint, searchRow, statusLabel, resultList);
            Platform.runLater(searchField::requestFocus);
        }

        private void doSearch() {
            String keyword = searchField.getText() == null ? "" : searchField.getText().trim();
            if (keyword.isEmpty()) {
                statusLabel.setText("请输入群号或群名称");
                return;
            }
            statusLabel.setText("搜索中…");
            ctx.searchGroups(keyword).whenComplete((groups, error) -> Platform.runLater(() -> {
                if (error != null) {
                    statusLabel.setText("搜索失败：" + ClientErrors.describe(error));
                    return;
                }
                List<ChatGroup> found = groups == null ? List.of() : groups;
                results.setAll(found);
                statusLabel.setText(found.isEmpty() ? "没有找到匹配的群" : "找到 " + found.size() + " 个群");
            }));
        }

        private final class ResultCell extends ListCell<ChatGroup> {
            @Override
            protected void updateItem(ChatGroup group, boolean empty) {
                super.updateItem(group, empty);
                if (empty || group == null) {
                    setGraphic(null);
                    return;
                }
                Label avatar = new Label(Avatars.initial(group.name()));
                avatar.getStyleClass().add("avatar");
                avatar.setMinSize(40, 40);
                avatar.setPrefSize(40, 40);
                avatar.setMaxSize(40, 40);
                Avatars.apply(avatar, group.id(), group.avatar());

                Label name = new Label(group.name());
                name.getStyleClass().add("conv-title");
                Label meta = new Label("群号 " + group.id() + " · " + group.memberCount() + " 人");
                meta.getStyleClass().add("conv-preview");
                VBox texts = new VBox(2, name, meta);
                HBox.setHgrow(texts, Priority.ALWAYS);

                boolean joined = ctx.store().find(ChatType.GROUP, group.id()) != null;
                Button join = new Button(joined ? "已加入" : "加入");
                join.getStyleClass().add("small-primary-button");
                join.setDisable(joined);
                if (!joined) {
                    join.setOnAction(e -> {
                        join.setDisable(true);
                        join.setText("申请中…");
                        ctx.requestJoinGroup(group.id()).whenComplete((request, error) ->
                                Platform.runLater(() -> {
                                    if (error != null) {
                                        join.setDisable(false);
                                        join.setText("加入");
                                        String message = ClientErrors.describe(error);
                                        if (message != null && message.contains("已经在群里")) {
                                            join.setText("已加入");
                                            join.setDisable(true);
                                        }
                                        statusLabel.setText("申请失败：" + message);
                                    } else {
                                        join.setText("等待处理");
                                        join.setDisable(true);
                                        statusLabel.setText("已发送申请，等待群主/管理员处理");
                                    }
                                }));
                    });
                }

                HBox row = new HBox(12, avatar, texts, join);
                row.setAlignment(Pos.CENTER_LEFT);
                row.setPadding(new Insets(6, 8, 6, 8));
                setGraphic(row);
            }
        }
    }
}
