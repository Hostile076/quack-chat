package io.quack.client.ui;

import io.quack.client.AppContext;
import io.quack.client.ClientErrors;
import io.quack.common.model.ChatMessage;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/** 群精华消息：群主可以添加（在消息右键菜单里）和移除，成员都能看 */
public final class EssenceDialog {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("MM-dd HH:mm")
            .withZone(ZoneId.systemDefault());

    private EssenceDialog() {
    }

    public static void show(Window owner, AppContext ctx, String groupId, boolean canManage) {
        ModalShell shell = new ModalShell(owner, "精华消息", 560, 600);

        Label hint = new Label(canManage
                ? "群主可以在聊天消息的右键菜单里把消息设为精华"
                : "群主整理的精华消息");
        hint.getStyleClass().add("modal-hint");

        ListView<ChatMessage> list = new ListView<>();
        list.getStyleClass().add("conversation-list");
        list.setPlaceholder(new Label("还没有精华消息"));
        VBox.setVgrow(list, Priority.ALWAYS);

        list.setCellFactory(view -> new ListCell<>() {
            @Override
            protected void updateItem(ChatMessage message, boolean empty) {
                super.updateItem(message, empty);
                if (empty || message == null) {
                    setGraphic(null);
                    return;
                }
                Label who = new Label(message.fromName());
                who.getStyleClass().add("conv-title");
                Label content = new Label(message.content());
                content.getStyleClass().add("conv-preview");
                content.setWrapText(true);
                Label time = new Label(TIME.format(Instant.ofEpochMilli(message.ts())));
                time.getStyleClass().add("conv-time");

                VBox texts = new VBox(2, who, content, time);
                HBox.setHgrow(texts, Priority.ALWAYS);

                HBox row = new HBox(10, texts);
                if (canManage) {
                    Button remove = new Button("移除");
                    remove.getStyleClass().add("title-button");
                    remove.setOnAction(e -> ctx.removeEssence(groupId, message.id())
                            .whenComplete((remaining, error) -> Platform.runLater(() -> {
                                if (error != null) {
                                    Toast.show(remove, "移除失败：" + ClientErrors.describe(error));
                                } else {
                                    list.getItems().setAll(remaining);
                                }
                            })));
                    row.getChildren().add(remove);
                }
                row.setPadding(new Insets(6, 8, 6, 8));
                setGraphic(row);
            }
        });

        VBox content = new VBox(10, hint, list);
        content.setPadding(new Insets(16));
        content.getStyleClass().add("modal-body");
        shell.setContent(content);

        ctx.loadEssence(groupId).whenComplete((messages, error) -> Platform.runLater(() -> {
            if (error != null) {
                Toast.show(list, "加载失败：" + ClientErrors.describe(error));
            } else {
                list.getItems().setAll(messages);
            }
        }));

        shell.showAndWait();
    }
}
