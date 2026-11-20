package io.quack.client.ui;

import io.quack.client.store.Conversation;
import io.quack.common.model.ChatMessage;
import io.quack.common.model.MsgType;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.awt.Desktop;
import java.net.URI;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/** 群文件 / 群相册 / 闪传：把聊天里发过的文件、图片按类型列出来 */
public final class FilesDialog {

    public enum Mode {
        FILES, IMAGES
    }

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("MM-dd HH:mm")
            .withZone(ZoneId.systemDefault());

    private FilesDialog() {
    }

    public static void show(Window owner, Conversation conversation, Mode mode) {
        if (conversation == null) {
            return;
        }
        boolean images = mode == Mode.IMAGES;
        ModalShell shell = new ModalShell(owner,
                images ? "群相册" : "群文件", 560, 620);

        Label hint = new Label(images
                ? "本群聊天里发过的图片（点击查看大图）"
                : "本群聊天里发过的文件（点击下载）");
        hint.getStyleClass().add("modal-hint");

        List<ChatMessage> matched = conversation.messages().stream()
                .filter(m -> images ? m.msgType() == MsgType.IMAGE : m.msgType() == MsgType.FILE)
                .toList();

        ListView<ChatMessage> list = new ListView<>();
        list.getStyleClass().add("conversation-list");
        list.setItems(javafx.collections.FXCollections.observableArrayList(matched));
        list.setPlaceholder(new Label(images ? "还没有发过图片" : "还没有发过文件"));
        VBox.setVgrow(list, javafx.scene.layout.Priority.ALWAYS);

        list.setCellFactory(view -> new ListCell<>() {
            @Override
            protected void updateItem(ChatMessage message, boolean empty) {
                super.updateItem(message, empty);
                if (empty || message == null) {
                    setGraphic(null);
                    return;
                }
                final String url = io.quack.client.net.MediaUrls.resolve(
                        message.content() == null ? "" : message.content().split("\\|")[0]);
                String name = message.msgType() == MsgType.FILE
                        ? (message.content().split("\\|").length > 1 ? message.content().split("\\|")[1] : "文件")
                        : "图片";

                HBox row = new HBox(10);
                if (images) {
                    ImageView preview = new ImageView(io.quack.client.net.ImageCache.load(
                            io.quack.client.net.MediaUrls.withWidth(url, 128), 128, 128));
                    preview.setFitWidth(64);
                    preview.setFitHeight(64);
                    row.getChildren().add(preview);
                } else {
                    Label icon = new Label("📄");
                    icon.setStyle("-fx-font-size: 24px;");
                    row.getChildren().add(icon);
                }

                Label title = new Label(name);
                title.getStyleClass().add("conv-title");
                Label meta = new Label(message.fromName() + " · "
                        + TIME.format(Instant.ofEpochMilli(message.ts())));
                meta.getStyleClass().add("conv-preview");
                VBox texts = new VBox(2, title, meta);
                HBox.setHgrow(texts, javafx.scene.layout.Priority.ALWAYS);

                row.getChildren().add(texts);
                row.setAlignment(Pos.CENTER_LEFT);
                row.setPadding(new Insets(6, 8, 6, 8));
                row.setOnMouseClicked(e -> {
                    if (images) {
                        ImageViewerDialog.show(shell.stage(), url);
                    } else {
                        open(url);
                    }
                });
                setGraphic(row);
            }
        });

        VBox content = new VBox(10, hint, list);
        content.setPadding(new Insets(16));
        content.getStyleClass().add("modal-body");
        shell.setContent(content);
        shell.showAndWait();
    }

    private static void open(String url) {
        try {
            Desktop.getDesktop().browse(URI.create(url));
        } catch (Exception ignored) {
            // 打不开就算了，界面不崩
        }
    }
}
