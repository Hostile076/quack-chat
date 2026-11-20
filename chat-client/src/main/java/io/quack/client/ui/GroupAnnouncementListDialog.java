package io.quack.client.ui;

import io.quack.client.AppContext;
import io.quack.client.ClientErrors;
import io.quack.common.model.GroupAnnouncement;
import io.quack.common.model.GroupDetail;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.function.Consumer;

/** 查看全部群公告；群主右上角「+」发布新公告 */
public final class GroupAnnouncementListDialog {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("MM-dd HH:mm")
            .withZone(ZoneId.systemDefault());

    private GroupAnnouncementListDialog() {
    }

    public static void show(Window owner, AppContext ctx, GroupDetail detail, boolean isOwner,
                            Consumer<GroupDetail> onUpdated) {
        ModalShell shell = new ModalShell(owner, "群公告", 640, 640);
        GroupDetail[] current = {detail};

        Label title = new Label("群公告");
        title.getStyleClass().add("profile-name");
        Label count = new Label();
        count.getStyleClass().add("profile-sub");
        VBox texts = new VBox(2, title, count);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Button plus = new Button("+");
        plus.getStyleClass().add("icon-plus-button");
        plus.setTooltip(Popups.tooltip("发布新公告"));
        plus.setVisible(isOwner);
        plus.setManaged(isOwner);
        HBox header = new HBox(10, texts, spacer, plus);
        header.setAlignment(Pos.CENTER_LEFT);

        VBox list = new VBox(10);
        ScrollPane scroll = new ScrollPane(list);
        scroll.setFitToWidth(true);
        VBox.setVgrow(scroll, Priority.ALWAYS);

        Runnable[] render = new Runnable[1];
        render[0] = () -> {
            List<GroupAnnouncement> announcements = current[0].announcementList();
            count.setText(announcements.isEmpty() ? "还没有公告" : "共 " + announcements.size() + " 条");
            list.getChildren().clear();
            if (announcements.isEmpty()) {
                Label empty = new Label("还没有发布过群公告");
                empty.getStyleClass().add("empty-state");
                list.getChildren().add(empty);
                return;
            }
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
        };
        render[0].run();

        plus.setOnAction(e -> showEditor(shell.stage(), ctx, current[0].id(), updated -> {
            current[0] = updated;
            if (onUpdated != null) {
                onUpdated.accept(updated);
            }
            render[0].run();
        }));

        VBox content = new VBox(12, header, scroll);
        content.setPadding(new Insets(16));
        content.getStyleClass().add("modal-body");
        shell.setContent(content);
        shell.showAndWait();
    }

    private static void showEditor(Window owner, AppContext ctx, String groupId,
                                   Consumer<GroupDetail> onPosted) {
        ModalShell shell = new ModalShell(owner, "发布群公告", 560, 420);
        Label title = new Label("发布群公告");
        title.getStyleClass().add("profile-name");
        TextArea input = new TextArea();
        input.setPromptText("输入公告内容（最多 500 字）");
        input.setWrapText(true);
        input.setPrefRowCount(5);
        input.getStyleClass().add("message-input");
        Label error = new Label();
        error.getStyleClass().add("error");
        error.setWrapText(true);
        Button cancel = new Button("取消");
        cancel.getStyleClass().add("secondary-button");
        cancel.setOnAction(e -> shell.close());
        Button post = new Button("发布");
        post.getStyleClass().add("primary-button");
        post.setOnAction(e -> {
            String text = input.getText() == null ? "" : input.getText().trim();
            if (text.isEmpty()) {
                error.setText("公告内容不能为空");
                return;
            }
            post.setDisable(true);
            ctx.addGroupAnnouncement(groupId, text).whenComplete((updated, ex) ->
                    Platform.runLater(() -> {
                        post.setDisable(false);
                        if (ex != null) {
                            error.setText("发布失败：" + ClientErrors.describe(ex));
                            return;
                        }
                        if (onPosted != null) {
                            onPosted.accept(updated);
                        }
                        shell.close();
                    }));
        });
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox actions = new HBox(8, spacer, cancel, post);
        actions.setAlignment(Pos.CENTER_RIGHT);
        VBox content = new VBox(10, title, input, error, actions);
        content.setPadding(new Insets(14));
        content.getStyleClass().add("modal-body");
        shell.setContent(content);
        shell.showAndWait();
    }
}
