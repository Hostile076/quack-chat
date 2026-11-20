package io.quack.client.ui;

import io.quack.client.AppContext;
import io.quack.client.ClientErrors;
import io.quack.client.store.AccountStore;
import io.quack.common.model.User;
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

/**
 * 设置：一个入口列表，分三级
 *
 * <ul>
 *   <li>个人信息 —— 改用户名、头像、昵称、签名</li>
 *   <li>切换账号 —— 二级页，列出本机保存的账号</li>
 *   <li>通用 —— 预留（主题、语言、通知等后续接）</li>
 * </ul>
 */
public final class AccountDialog {

    private AccountDialog() {
    }

    public static void show(Window owner, AppContext ctx, Runnable onAccountChanged, javafx.scene.Node anchor) {
        ModalShell shell = new ModalShell(owner, "设置", 460, 520);
        User me = ctx.store().me();

        VBox content = new VBox(10);
        content.setPadding(new Insets(18));
        content.getStyleClass().add("modal-body");

        Label title = new Label("设置");
        title.getStyleClass().add("profile-name");

        VBox list = new VBox(6);
        list.getChildren().addAll(
                settingRow("user", "个人信息", "用户名、头像、昵称、签名",
                        () -> {
                            shell.close();
                            openPersonal(owner, ctx);
                        }),
                settingRow("swap", "切换账号", "共 " + ctx.accounts().accounts().size() + " 个账号",
                        () -> {
                            shell.close();
                            showAccounts(owner, ctx, onAccountChanged);
                        }),
                settingRow("gear", "通用", "外观、动态效果、提示音、字号",
                        () -> {
                            shell.close();
                            GeneralSettingsDialog.show(owner, ctx);
                        }));

        Label tip = new Label(me == null ? "" : "当前账号：" + me.displayName() + "（QC " + me.qcId() + "）");
        tip.getStyleClass().add("modal-hint");

        content.getChildren().addAll(title, list, tip);
        shell.setContent(content);
        shell.showAbove(anchor);
    }

    // ------------------------------------------------------------------
    // 个人信息
    // ------------------------------------------------------------------

    private static void openPersonal(Window owner, AppContext ctx) {
        User me = ctx.store().me();
        if (me == null) {
            return;
        }
        ctx.loadProfile(me.id()).whenComplete((profile, error) -> Platform.runLater(() -> {
            if (error != null || profile == null) {
                Toast.show(owner == null || owner.getScene() == null
                        ? null : owner.getScene().getRoot(), "加载资料失败：" + ClientErrors.describe(error));
                return;
            }
            ProfileView.showEditor(owner, ctx, profile);
        }));
    }

    // ------------------------------------------------------------------
    // 切换账号（二级页）
    // ------------------------------------------------------------------

    private static void showAccounts(Window owner, AppContext ctx, Runnable onAccountChanged) {
        ModalShell shell = new ModalShell(owner, "切换账号", 440, 520);
        User me = ctx.store().me();

        VBox content = new VBox(12);
        content.setPadding(new Insets(18));
        content.getStyleClass().add("modal-body");

        Button back = new Button("‹ 返回设置");
        back.getStyleClass().add("title-button");
        back.setOnAction(e -> {
            shell.close();
            show(owner, ctx, onAccountChanged, null);
        });

        VBox accountList = new VBox(6);
        for (AccountStore.SavedAccount account : ctx.accounts().accounts()) {
            boolean isCurrent = me != null && me.qcId() == account.qcId();

            Label avatar = new Label(Avatars.initial(account.display()));
            avatar.getStyleClass().add("avatar");
            avatar.setMinSize(32, 32);
            avatar.setPrefSize(32, 32);
            avatar.setMaxSize(32, 32);
            Avatars.apply(avatar, String.valueOf(account.qcId()), account.avatar());

            Label name = new Label(account.display());
            name.getStyleClass().add("conv-title");
            Label qc = new Label("QC " + account.qcId());
            qc.getStyleClass().add("conv-preview");
            VBox texts = new VBox(1, name, qc);
            HBox.setHgrow(texts, Priority.ALWAYS);

            Label badge = new Label(isCurrent ? "当前" : "切换");
            badge.getStyleClass().add(isCurrent ? "chip" : "chip-brand");

            HBox row = new HBox(10, avatar, texts, badge);
            row.setAlignment(Pos.CENTER_LEFT);
            row.setPadding(new Insets(8, 12, 8, 12));
            row.getStyleClass().add("option-row");
            if (!isCurrent) {
                row.setCursor(javafx.scene.Cursor.HAND);
                row.setOnMouseClicked(e -> {
                    shell.close();
                    ctx.switchAccount(account.qcId());
                    if (onAccountChanged != null) {
                        onAccountChanged.run();
                    }
                });
            }
            accountList.getChildren().add(row);
        }
        if (accountList.getChildren().isEmpty()) {
            Label empty = new Label("还没有保存过其它账号");
            empty.getStyleClass().add("modal-hint");
            accountList.getChildren().add(empty);
        }

        ScrollPane scroll = new ScrollPane(accountList);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("edge-to-edge");
        VBox.setVgrow(scroll, Priority.ALWAYS);

        Button add = new Button("添加账号…");
        add.getStyleClass().add("secondary-button");
        add.setOnAction(e -> {
            shell.close();
            ctx.logout();
            if (onAccountChanged != null) {
                onAccountChanged.run();
            }
        });

        Button logout = new Button("退出登录");
        logout.getStyleClass().add("danger-button");
        logout.setOnAction(e -> {
            shell.close();
            ctx.logout();
            if (onAccountChanged != null) {
                onAccountChanged.run();
            }
        });

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox actions = new HBox(10, add, spacer, logout);

        content.getChildren().addAll(back, scroll, actions);
        shell.setContent(content);
        shell.showAndWait();
    }

    // ------------------------------------------------------------------

    private static HBox settingRow(String iconName, String title, String subtitle, Runnable action) {
        Label iconLabel = new Label();
        iconLabel.setGraphic(Icons.of(iconName, 19));
        Label titleLabel = new Label(title);
        titleLabel.getStyleClass().add("option-title");
        Label subLabel = new Label(subtitle);
        subLabel.getStyleClass().add("option-value");

        VBox texts = new VBox(2, titleLabel, subLabel);
        HBox.setHgrow(texts, Priority.ALWAYS);

        Label arrow = new Label("›");
        arrow.getStyleClass().add("option-arrow");

        HBox row = new HBox(10, iconLabel, texts, arrow);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("option-row");
        row.setPadding(new Insets(12, 16, 12, 16));
        row.setCursor(javafx.scene.Cursor.HAND);
        row.setOnMouseClicked(e -> action.run());
        return row;
    }
}
