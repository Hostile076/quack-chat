package io.quack.client.ui;

import io.quack.client.AppContext;
import io.quack.client.ClientErrors;
import io.quack.common.model.GroupJoinRequest;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/** 待处理：我发出的入群申请 + 需要我审批的申请 */
public final class GroupRequestsDialog {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("MM-dd HH:mm")
            .withZone(ZoneId.systemDefault());

    private GroupRequestsDialog() {
    }

    public static void show(Window owner, AppContext ctx) {
        ModalShell shell = new ModalShell(owner, "待处理", 660, 620);
        Label title = new Label("入群申请");
        title.getStyleClass().add("profile-name");
        Label hint = new Label("我发出的申请显示“等待处理”；群主/管理员可以同意或不同意");
        hint.getStyleClass().add("profile-sub");
        hint.setWrapText(true);

        VBox list = new VBox(10);
        ScrollPane scroll = new ScrollPane(list);
        scroll.setFitToWidth(true);
        VBox.setVgrow(scroll, Priority.ALWAYS);

        VBox content = new VBox(12, title, hint, scroll);
        content.setPadding(new Insets(16));
        content.getStyleClass().add("modal-body");
        shell.setContent(content);

        load(ctx, list);
        shell.showAndWait();
    }

    private static void load(AppContext ctx, VBox list) {
        ctx.loadGroupJoinRequests().whenComplete((requests, error) -> Platform.runLater(() -> {
            list.getChildren().clear();
            if (error != null) {
                Label fail = new Label("加载失败：" + ClientErrors.describe(error));
                fail.getStyleClass().add("empty-state");
                list.getChildren().add(fail);
                return;
            }
            List<GroupJoinRequest> items = requests == null ? List.of() : requests;
            if (items.isEmpty()) {
                Label empty = new Label("没有待处理的入群申请");
                empty.getStyleClass().add("empty-state");
                list.getChildren().add(empty);
                return;
            }
            String myId = ctx.me() == null ? "" : ctx.me().id();
            for (GroupJoinRequest request : items) {
                list.getChildren().add(card(ctx, request, myId, list));
            }
        }));
    }

    private static HBox card(AppContext ctx, GroupJoinRequest request, String myId, VBox list) {
        Label group = new Label(request.groupName() + "（" + request.groupId() + "）");
        group.getStyleClass().add("conv-title");
        Label who = new Label(request.userName() + " · QC " + request.userId() + " · "
                + TIME.format(Instant.ofEpochMilli(request.ts())));
        who.getStyleClass().add("conv-preview");
        VBox texts = new VBox(2, group, who);
        HBox.setHgrow(texts, Priority.ALWAYS);

        HBox row = new HBox(10, texts);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(10));
        row.getStyleClass().add("moment-card");

        if (request.pending() && myId.equals(request.userId())) {
            Label waiting = new Label("等待处理");
            waiting.getStyleClass().add("conv-time");
            row.getChildren().add(waiting);
            return row;
        }
        if (request.pending()) {
            Button accept = new Button("同意");
            accept.getStyleClass().add("small-primary-button");
            Button reject = new Button("不同意");
            reject.getStyleClass().add("secondary-button");
            accept.setOnAction(e -> handle(ctx, request, true, accept, reject, list));
            reject.setOnAction(e -> handle(ctx, request, false, accept, reject, list));
            row.getChildren().addAll(accept, reject);
            return row;
        }
        Label status = new Label(GroupJoinRequest.APPROVED.equalsIgnoreCase(request.status())
                ? "已同意" : "已拒绝");
        status.getStyleClass().add("conv-time");
        row.getChildren().add(status);
        return row;
    }

    private static void handle(AppContext ctx, GroupJoinRequest request, boolean accept,
                               Button acceptButton, Button rejectButton, VBox list) {
        acceptButton.setDisable(true);
        rejectButton.setDisable(true);
        ctx.handleGroupJoinRequest(request.id(), accept).whenComplete((updated, error) ->
                Platform.runLater(() -> {
                    if (error != null) {
                        acceptButton.setDisable(false);
                        rejectButton.setDisable(false);
                        Toast.show(acceptButton, "处理失败：" + ClientErrors.describe(error));
                        return;
                    }
                    load(ctx, list);
                }));
    }
}
