package io.quack.client.ui;

import io.quack.client.AppContext;
import io.quack.client.ClientErrors;
import io.quack.common.model.GroupDetail;
import io.quack.common.model.GroupMember;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.util.List;
import java.util.function.Consumer;

/** 群管理名单：群主可「+」任命、取消管理员，并可转让群主；普通成员只读 */
public final class GroupAdminListDialog {

    private GroupAdminListDialog() {
    }

    public static void show(Window owner, AppContext ctx, GroupDetail detail, boolean isOwner,
                            Consumer<GroupDetail> onUpdated) {
        ModalShell shell = new ModalShell(owner, "群管理", 620, 620);
        GroupDetail[] current = {detail};

        Label title = new Label("群管理");
        title.getStyleClass().add("profile-name");
        Label hint = new Label();
        hint.getStyleClass().add("profile-sub");
        VBox texts = new VBox(2, title, hint);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Button plus = new Button("+");
        plus.getStyleClass().add("icon-plus-button");
        plus.setTooltip(Popups.tooltip("任命管理员"));
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
            GroupDetail group = current[0];
            List<GroupMember> admins = group.members() == null ? List.of()
                    : group.members().stream()
                    .filter(member -> GroupMember.ADMIN.equals(member.role()))
                    .toList();
            hint.setText("群主 1 人 · 管理员 " + admins.size() + " 人"
                    + (isOwner ? "；管理员拥有和群主同等的管理权限" : "（只读）"));
            list.getChildren().clear();

            GroupMember ownerMember = group.members() == null ? null
                    : group.members().stream()
                    .filter(m -> GroupMember.OWNER.equals(m.role()))
                    .findFirst().orElse(null);
            if (ownerMember != null) {
                list.getChildren().add(adminRow(ctx, group, ownerMember, false, isOwner,
                        current, render, onUpdated));
            }
            if (admins.isEmpty()) {
                Label empty = new Label("还没有管理员");
                empty.getStyleClass().add("empty-state");
                list.getChildren().add(empty);
            }
            for (GroupMember admin : admins) {
                list.getChildren().add(adminRow(ctx, group, admin, true, isOwner,
                        current, render, onUpdated));
            }
        };
        render[0].run();

        plus.setOnAction(e -> showAddPicker(shell.stage(), ctx, current[0], updated -> {
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

    private static HBox adminRow(AppContext ctx, GroupDetail group, GroupMember member,
                                 boolean admin, boolean owner, GroupDetail[] current,
                                 Runnable[] render, Consumer<GroupDetail> onUpdated) {
        Label avatar = new Label(Avatars.initial(member.displayName()));
        avatar.getStyleClass().add("avatar");
        avatar.setMinSize(40, 40);
        avatar.setPrefSize(40, 40);
        avatar.setMaxSize(40, 40);
        Avatars.apply(avatar, member.userId(), member.avatar());

        Label name = new Label(member.displayName() + (admin ? "" : "（群主）"));
        name.getStyleClass().add("conv-title");
        Label meta = new Label("QC " + member.userId()
                + (admin ? " · 管理员" : " · 群主"));
        meta.getStyleClass().add("conv-preview");
        VBox texts = new VBox(2, name, meta);
        HBox.setHgrow(texts, Priority.ALWAYS);

        HBox row = new HBox(12, avatar, texts);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(10));
        row.getStyleClass().add("moment-card");

        if (owner && admin) {
            Button cancel = new Button("取消");
            cancel.getStyleClass().add("secondary-button");
            cancel.setOnAction(e -> ctx.removeGroupAdmin(group.id(), member.userId())
                    .whenComplete((updated, error) -> Platform.runLater(() -> {
                        if (error != null) {
                            Toast.show(cancel, "取消失败：" + ClientErrors.describe(error));
                            return;
                        }
                        current[0] = updated;
                        if (onUpdated != null) {
                            onUpdated.accept(updated);
                        }
                        render[0].run();
                    })));
            Button transfer = new Button("转让群主");
            transfer.getStyleClass().add("small-primary-button");
            transfer.setOnAction(e -> ConfirmDialog.confirm(transfer.getScene().getWindow(),
                    "转让群主", "确定把群主转让给 " + member.displayName() + " 吗？转让后你将成为普通成员。",
                    "转让", () -> ctx.transferOwner(group.id(), member.userId())
                            .whenComplete((updated, error) -> Platform.runLater(() -> {
                                if (error != null) {
                                    Toast.show(transfer, "转让失败：" + ClientErrors.describe(error));
                                    return;
                                }
                                current[0] = updated;
                                if (onUpdated != null) {
                                    onUpdated.accept(updated);
                                }
                                render[0].run();
                            }))));
            row.getChildren().addAll(transfer, cancel);
        }
        return row;
    }

    private static void showAddPicker(Window owner, AppContext ctx, GroupDetail detail,
                                      Consumer<GroupDetail> onUpdated) {
        ModalShell shell = new ModalShell(owner, "任命管理员", 520, 560);
        Label title = new Label("选择要任命为管理员的成员");
        title.getStyleClass().add("profile-name");
        VBox list = new VBox(8);
        ScrollPane scroll = new ScrollPane(list);
        scroll.setFitToWidth(true);
        VBox.setVgrow(scroll, Priority.ALWAYS);

        List<GroupMember> candidates = detail.members() == null ? List.of()
                : detail.members().stream()
                .filter(member -> GroupMember.MEMBER.equals(member.role()))
                .toList();
        if (candidates.isEmpty()) {
            list.getChildren().add(new Label("没有可任命的成员"));
        }
        for (GroupMember member : candidates) {
            Label name = new Label(member.displayName() + "  QC " + member.userId());
            name.getStyleClass().add("conv-title");
            Region spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);
            Button add = new Button("任命");
            add.getStyleClass().add("small-primary-button");
            add.setOnAction(e -> ctx.addGroupAdmin(detail.id(), member.userId())
                    .whenComplete((updated, error) -> Platform.runLater(() -> {
                        if (error != null) {
                            Toast.show(add, "任命失败：" + ClientErrors.describe(error));
                            return;
                        }
                        if (onUpdated != null) {
                            onUpdated.accept(updated);
                        }
                        shell.close();
                    })));
            HBox row = new HBox(10, name, spacer, add);
            row.setAlignment(Pos.CENTER_LEFT);
            row.setPadding(new Insets(8));
            row.getStyleClass().add("contact-row");
            list.getChildren().add(row);
        }
        VBox content = new VBox(12, title, scroll);
        content.setPadding(new Insets(14));
        content.getStyleClass().add("modal-body");
        shell.setContent(content);
        shell.showAndWait();
    }
}
