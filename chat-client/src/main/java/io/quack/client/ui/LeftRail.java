package io.quack.client.ui;

import io.quack.client.AppContext;
import io.quack.client.store.AccountStore;
import io.quack.common.model.User;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.SVGPath;

import java.util.function.Consumer;

/**
 * 最左侧的窄导航栏：聊天 / 通讯录，底部是「设置」和当前账号。
 *
 * <p>交互约定：
 * <ul>
 *   <li>头像 <b>双击</b> → 打开自己的个人主页；</li>
 *   <li>头像上方的 <b>齿轮</b> → 账号与设置（切换 / 添加 / 退出登录）。</li>
 * </ul>
 */
public class LeftRail extends VBox {

    private final AppContext ctx;
    private final Label chatButton;
    private final Label contactsButton;
    private final Label spaceButton;
    private final Label avatar;
    private final Consumer<Boolean> onSectionChanged;

    public LeftRail(AppContext ctx, Consumer<Boolean> onSectionChanged) {
        this.ctx = ctx;
        this.onSectionChanged = onSectionChanged;
        getStyleClass().add("rail");
        setSpacing(6);
        setPadding(new Insets(12, 8, 12, 8));
        setPrefWidth(74);
        setMinWidth(74);
        setMaxWidth(74);
        setAlignment(Pos.TOP_CENTER);

        Label logo = new Label("Q");
        logo.getStyleClass().add("rail-logo");

        chatButton = railItem(Icons.of("chat", 21), "聊天");
        contactsButton = railItem(Icons.of("contacts", 21), "通讯录");
        spaceButton = railItem(Icons.of("globe", 21), "空间");
        chatButton.setOnMouseClicked(e -> select(true));
        contactsButton.setOnMouseClicked(e -> select(false));
        spaceButton.setOnMouseClicked(e -> {
            if (ctx.window() != null && ctx.window().stage() != null) {
                MomentSpaceView.showTimeline(ctx.window().stage(), ctx);
            }
        });

        Region spacer = new Region();
        VBox.setVgrow(spacer, Priority.ALWAYS);

        Label gear = gearButton();
        gear.setOnMouseClicked(e -> openAccountSettings(gear));

        avatar = new Label(Avatars.initial(displayName()));
        avatar.getStyleClass().addAll("avatar", "rail-avatar");
        avatar.setMinSize(40, 40);
        avatar.setPrefSize(40, 40);
        avatar.setMaxSize(40, 40);
        User me = ctx.store().me();
        if (me != null) {
            Avatars.apply(avatar, me.id(), me.avatar());
        }
        avatar.setOnMouseClicked(e -> {
            if (e.getClickCount() >= 2) {
                openMyProfile();
            }
        });
        Tooltip.install(avatar, Popups.tooltip("双击查看我的资料"));

        getChildren().addAll(logo, chatButton, contactsButton, spaceButton, spacer, gear, avatar);
        select(true);
    }

    public void refreshAccount() {
        User me = ctx.store().me();
        avatar.setText(Avatars.initial(me == null ? "?" : me.displayName()));
        if (me != null) {
            Avatars.apply(avatar, me.id(), me.avatar());
        }
    }

    private String displayName() {
        User me = ctx.store().me();
        return me == null ? "?" : me.displayName();
    }

    /** 竖排「图标 + 文字」的导航项，用 Label 而不是 Button，避免抢走双击事件 */
    private Label railItem(javafx.scene.Node icon, String text) {
        Label iconLabel = new Label();
        iconLabel.setGraphic(icon);
        iconLabel.getStyleClass().add("rail-icon");
        Label textLabel = new Label(text);
        textLabel.getStyleClass().add("rail-text");

        VBox box = new VBox(3, iconLabel, textLabel);
        box.setAlignment(Pos.CENTER);

        Label item = new Label();
        item.setGraphic(box);
        item.getStyleClass().add("rail-item");
        item.setMaxWidth(Double.MAX_VALUE);
        item.setAlignment(Pos.CENTER);
        // 按下即缩：导航的反馈要比 click 更早到
        io.quack.client.ui.motion.Motion.pressable(item, 0.94);
        return item;
    }

    /** 齿轮：用 SVG 画，避免 emoji 字体在不同机器上粗细不一 */
    private Label gearButton() {
        SVGPath gearShape = new SVGPath();
        gearShape.setContent(
                "M19.14,12.94a7.07,7.07,0,0,0,0-1.88l2-1.58a0.5,0.5,0,0,0,.12-0.64"
                        + "l-1.9-3.29a0.5,0.5,0,0,0-.61-0.22l-2.36,0.95a7,7,0,0,0-1.62-.94"
                        + "l-.36-2.51A0.5,0.5,0,0,0,13.92,2h-3.8a0.5,0.5,0,0,0-.5,0.43"
                        + "l-.36,2.51a7,7,0,0,0-1.62.94L5.28,4.93a0.5,0.5,0,0,0-.61.22"
                        + "L2.77,8.44a0.5,0.5,0,0,0,.12.64l2,1.58a7.07,7.07,0,0,0,0,1.88"
                        + "l-2,1.58a0.5,0.5,0,0,0-.12.64l1.9,3.29a0.5,0.5,0,0,0,.61.22"
                        + "l2.36-.95a7,7,0,0,0,1.62.94l.36,2.51a0.5,0.5,0,0,0,.5.43h3.8"
                        + "a0.5,0.5,0,0,0,.5-.43l.36-2.51a7,7,0,0,0,1.62-.94l2.36.95"
                        + "a0.5,0.5,0,0,0,.61-.22l1.9-3.29a0.5,0.5,0,0,0-.12-.64Z"
                        + "M12,15.5A3.5,3.5,0,1,1,15.5,12,3.5,3.5,0,0,1,12,15.5Z");
        gearShape.getStyleClass().add("rail-gear-shape");
        gearShape.setScaleX(0.78);
        gearShape.setScaleY(0.78);

        StackPane holder = new StackPane(gearShape);
        holder.setPrefSize(26, 26);
        holder.setMinSize(26, 26);
        holder.setMaxSize(26, 26);

        Label gear = new Label();
        gear.setGraphic(holder);
        gear.getStyleClass().add("rail-gear");
        gear.setAlignment(Pos.CENTER);
        gear.setMinSize(38, 38);
        gear.setPrefSize(38, 38);
        gear.setMaxSize(38, 38);
        Tooltip.install(gear, Popups.tooltip("账号与设置"));
        return gear;
    }

    /** true = 聊天，false = 通讯录 */
    public void select(boolean chats) {
        chatButton.getStyleClass().remove("rail-item-active");
        contactsButton.getStyleClass().remove("rail-item-active");
        (chats ? chatButton : contactsButton).getStyleClass().add("rail-item-active");
        if (onSectionChanged != null) {
            onSectionChanged.accept(chats);
        }
    }

    private void openMyProfile() {
        User me = ctx.store().me();
        if (me == null) {
            return;
        }
        javafx.stage.Window owner = getScene() == null ? null : getScene().getWindow();
        ProfileView.show(owner, ctx, me.id(), text -> {
            // 从自己主页发起聊天：拉出对应会话
            var conversation = ctx.store().find(io.quack.common.model.ChatType.PRIVATE, me.id());
            if (conversation != null) {
                onSectionChanged.accept(true);
            }
        });
    }

    private void openAccountSettings(javafx.scene.Node anchor) {
        javafx.stage.Window owner = getScene() == null ? null : getScene().getWindow();
        AccountDialog.show(owner, ctx, this::refreshAccount, anchor);
    }

    /** 供外部（比如登录后刷新）取当前账号列表的展示名 */
    public static String describe(AccountStore.SavedAccount account) {
        return account.display();
    }

    /** 头像右侧的在线小圆点用不到，占位保持布局稳定 */
    static HBox withDot(Label avatar) {
        Region dot = new Region();
        dot.getStyleClass().add("rail-dot");
        HBox box = new HBox(avatar);
        box.setAlignment(Pos.CENTER);
        return box;
    }
}
