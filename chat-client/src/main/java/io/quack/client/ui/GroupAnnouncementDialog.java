package io.quack.client.ui;

import io.quack.common.model.GroupAnnouncement;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/** 进群时弹出未读群公告（可多条，滚动查看），底部「收到」按钮关闭 */
public final class GroupAnnouncementDialog {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("MM-dd HH:mm")
            .withZone(ZoneId.systemDefault());

    private GroupAnnouncementDialog() {
    }

    public static ModalShell show(Window owner, String groupName, List<GroupAnnouncement> announcements,
                                  Runnable onReceived) {
        ModalShell shell = new ModalShell(owner, "群公告", 580, 480);
        Label title = new Label(groupName == null ? "群公告" : groupName);
        title.getStyleClass().add("profile-name");
        Label hint = new Label(announcements.size() + " 条公告");
        hint.getStyleClass().add("profile-sub");

        VBox list = new VBox(10);
        for (GroupAnnouncement announcement : announcements) {
            Label body = new Label(announcement.text() == null ? "" : announcement.text());
            body.setWrapText(true);
            body.getStyleClass().add("moment-text");
            Label meta = new Label((announcement.authorName() == null ? "群主" : announcement.authorName())
                    + " · " + (announcement.ts() <= 0 ? "" : TIME.format(
                    Instant.ofEpochMilli(announcement.ts()))));
            meta.getStyleClass().add("conv-time");
            VBox card = new VBox(6, body, meta);
            card.setPadding(new Insets(10));
            card.getStyleClass().add("moment-card");
            list.getChildren().add(card);
        }
        ScrollPane scroll = new ScrollPane(list);
        scroll.setFitToWidth(true);
        VBox.setVgrow(scroll, Priority.ALWAYS);

        Button received = new Button("收到");
        received.getStyleClass().add("primary-button");
        received.setMinWidth(120);
        received.setOnAction(e -> {
            if (onReceived != null) {
                onReceived.run();
            }
            shell.close();
        });
        HBox actions = new HBox(received);
        actions.setAlignment(Pos.CENTER_RIGHT);

        VBox content = new VBox(10, title, hint, scroll, actions);
        content.setPadding(new Insets(16));
        content.getStyleClass().add("modal-body");
        shell.setContent(content);
        shell.showAndWait();
        return shell;
    }
}
