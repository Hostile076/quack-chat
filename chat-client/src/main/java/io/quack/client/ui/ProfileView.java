package io.quack.client.ui;

import io.quack.client.AppContext;
import io.quack.client.store.Conversation;
import io.quack.client.ClientErrors;
import io.quack.common.model.ChatType;
import io.quack.common.model.UserProfile;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Circle;
import javafx.stage.Window;

import java.time.LocalDate;
import java.time.Period;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.function.Consumer;

/**
 * 个人主页（参考 QQ 资料页布局）。
 *
 * <p>从上到下：个性横幅 → 头像/昵称/QC 号 + 获赞 → 个性签名 → 基本信息 →
 * 特权/互动标识 → QC 空间（可发布/点赞/删除）→ 底部操作栏。
 */
public final class ProfileView extends BorderPane {

    private final AppContext ctx;
    private final String targetQcId;
    private final Consumer<String> onMessageUser;
    private final VBox content = new VBox(12);

    private UserProfile profile;

    private ProfileView(AppContext ctx, String targetQcId, Consumer<String> onMessageUser) {
        this(ctx, targetQcId, onMessageUser, null);
    }

    private ProfileView(AppContext ctx, String targetQcId, Consumer<String> onMessageUser, UserProfile preloaded) {
        this.ctx = ctx;
        this.targetQcId = targetQcId;
        this.onMessageUser = onMessageUser;
        this.profile = preloaded;
        getStyleClass().add("profile-root");

        ScrollPane scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("profile-scroll");
        setCenter(scroll);

        content.setPadding(new Insets(0, 0, 16, 0));
        if (preloaded != null) {
            render();
        } else {
            content.getChildren().add(placeholder("加载资料中…"));
            load();
        }
    }

    /** 供截图脚本离线渲染用 */
    public static ProfileView preview(AppContext ctx, UserProfile profile) {
        return new ProfileView(ctx, null, null, profile);
    }

    /** @param qcId null 表示看自己 */
    public static void show(Window owner, AppContext ctx, String qcId, Consumer<String> onMessageUser) {
        // 资料窗口不锁主窗口（modal=false），主窗口仍可拖动/操作
        ModalShell shell = new ModalShell(owner, "资料", 560, 780, false);
        ProfileView view = new ProfileView(ctx, qcId, onMessageUser);
        shell.setContent(view);
        view.setCloseAction(shell::close);
        shell.showAndWait();
    }

    private Runnable closeAction = () -> {
    };

    private void setCloseAction(Runnable closeAction) {
        this.closeAction = closeAction;
    }

    private void load() {
        String qcId = targetQcId == null || targetQcId.isBlank()
                ? (ctx.me() == null ? null : ctx.me().id())
                : targetQcId;
        if (qcId == null) {
            return;
        }
        ctx.loadProfile(qcId).whenComplete((result, error) -> Platform.runLater(() -> {
            if (error != null) {
                content.getChildren().setAll(placeholder("加载失败：" + ClientErrors.describe(error)));
                return;
            }
            this.profile = result;
            render();
        }));
    }

    private void render() {
        boolean self = ctx.me() != null && ctx.me().qcId() == profile.qcId();
        content.getChildren().setAll(
                banner(),
                card(self),
                signatureRow(self),
                infoRow(),
                optionRow("互动标识", interactionText(self), true),
                qcSpaceRow(self),
                bottomActions(self));
    }

    // ------------------------------------------------------------------
    // 各区块
    // ------------------------------------------------------------------

    private StackPane banner() {
        StackPane banner = new StackPane();
        banner.getStyleClass().add("profile-banner");
        banner.setMinHeight(140);
        banner.setPrefHeight(140);

        Circle big = new Circle(120);
        big.getStyleClass().add("banner-circle");
        big.setTranslateX(150);
        big.setTranslateY(-40);
        Circle small = new Circle(70);
        small.getStyleClass().add("banner-circle");
        small.setTranslateX(-140);
        small.setTranslateY(50);
        banner.getChildren().addAll(big, small);

        Label tag = new Label("Quack Chat");
        tag.getStyleClass().add("banner-tag");
        StackPane.setAlignment(tag, Pos.TOP_LEFT);
        StackPane.setMargin(tag, new Insets(16, 0, 0, 20));
        banner.getChildren().add(tag);
        return banner;
    }

    private HBox card(boolean self) {
        Label avatar = new Label(Avatars.initial(profile.displayName()));
        avatar.getStyleClass().addAll("avatar", "profile-avatar");
        avatar.setMinSize(84, 84);
        avatar.setPrefSize(84, 84);
        avatar.setMaxSize(84, 84);
        // 自己的头像：服务端资料优先，取不到就退回本地身份（刚上传完还没刷新时也能显示）
        String avatarUrl = profile.avatar();
        if (self && (avatarUrl == null || avatarUrl.isBlank()) && ctx.me() != null) {
            avatarUrl = ctx.me().avatar();
        }
        Avatars.apply(avatar, profile.id(), avatarUrl);

        // 有备注就用备注（会话标题），否则用对方昵称
        String displayName = profile.displayName();
        io.quack.client.store.Conversation conversation =
                ctx.store().find(ChatType.PRIVATE, profile.id());
        if (!self && conversation != null && conversation.getTitle() != null
                && !conversation.getTitle().isBlank()) {
            displayName = conversation.getTitle();
        }
        Label name = new Label(displayName);
        name.getStyleClass().add("profile-name");
        Label nickname = new Label("昵称：" + displayName);
        nickname.getStyleClass().add("profile-sub");
        Label qc = new Label("QC 号：" + profile.qcId());
        qc.getStyleClass().add("profile-sub");

        VBox texts = new VBox(3, name, nickname, qc);
        texts.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(texts, Priority.ALWAYS);

        Label thumb = new Label();
        thumb.setGraphic(Icons.of("heart", 20));
        thumb.getStyleClass().add("like-icon");
        Label likeCount = new Label(String.valueOf(profile.likes()));
        likeCount.getStyleClass().add("like-count");
        HBox likeBox = new HBox(4, thumb, likeCount);
        likeBox.setAlignment(Pos.CENTER);
        likeBox.setMaxHeight(javafx.scene.layout.Region.USE_PREF_SIZE);
        likeBox.setMaxWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
        likeBox.getStyleClass().add("like-box");
        if (self) {
            likeBox.setOpacity(0.55);
        } else {
            likeBox.setCursor(javafx.scene.Cursor.HAND);
            likeBox.setOnMouseClicked(e -> ctx.likeUser(profile.id()).whenComplete((likes, error) ->
                    Platform.runLater(() -> {
                        if (error == null && likes != null) {
                            likeCount.setText(String.valueOf(likes));
                        }
                    })));
        }

        HBox card = new HBox(16, avatar, texts, likeBox);
        card.setAlignment(Pos.CENTER_LEFT);
        card.setFillHeight(false);
        card.getStyleClass().add("profile-card");
        // 用内联样式设内边距：样式表里的 -fx-padding 会覆盖 setPadding
        card.setStyle("-fx-padding: 12 24 12 24;");
        card.setTranslateY(-46);
        card.setMaxWidth(Double.MAX_VALUE);
        return card;
    }

    private Label signatureRow(boolean self) {
        String text = profile.signature() == null || profile.signature().isBlank()
                ? "这个人很懒，还没有写个性签名" : profile.signature();
        Label signature = new Label(text);
        signature.getStyleClass().add("profile-signature");
        signature.setWrapText(true);
        signature.setPadding(new Insets(0, 20, 0, 20));
        signature.setTranslateY(-34);
        return signature;
    }

    /** 互动标识：用双方在本机保存的消息条数算，真实数据 */
    private String interactionText(boolean self) {
        if (self) {
            return "累计获赞 " + profile.likes();
        }
        Conversation conversation = ctx.store().find(ChatType.PRIVATE, profile.id());
        int count = conversation == null ? 0 : conversation.messages().size();
        if (count == 0) {
            return "还没聊过，打个招呼吧";
        }
        String level = count >= 100 ? "默契好友" : count >= 30 ? "常聊好友" : "新朋友";
        return level + " · 已互发 " + count + " 条消息";
    }

    private HBox infoRow() {
        String birthday = profile.birthday() == null ? "" : profile.birthday();
        LocalDate birthDate = parse(birthday);

        HBox row = new HBox(10);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(0, 20, 8, 20));
        row.setTranslateY(-26);

        // 只展示"确实有值"的信息：一堆"未填写 / 年龄未知"只会让资料页显得潦草
        java.util.List<String> values = new java.util.ArrayList<>();
        if (profile.gender() != null && !profile.gender().isBlank()) {
            values.add(genderIcon(profile.gender()) + " " + profile.gender().trim());
        }
        if (birthDate != null) {
            values.add(ageText(birthday));
            values.add(constellation(birthday));
            values.add(shortDate(birthday));
        }
        if (profile.region() != null && !profile.region().isBlank()) {
            values.add(profile.region().trim());
        }
        if (profile.identity() != null && !profile.identity().isBlank()) {
            values.add(profile.identity().trim());
        }
        if (values.isEmpty()) {
            row.setManaged(false);
            row.setVisible(false);
            return row;
        }
        for (String value : values) {
            row.getChildren().add(chip(value));
        }
        return row;
    }

    private Label chip(String text) {
        Label chip = new Label(text);
        chip.getStyleClass().add("profile-chip");
        return chip;
    }

    private HBox optionRow(String title, String value, boolean clickable) {
        Label name = new Label(title);
        name.getStyleClass().add("option-title");
        Label valueLabel = new Label(value);
        valueLabel.getStyleClass().add("option-value");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Label arrow = new Label(clickable ? "›" : "");
        arrow.getStyleClass().add("option-arrow");

        HBox row = new HBox(10, name, spacer, valueLabel, arrow);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("option-row");
        row.setPadding(new Insets(14, 20, 14, 20));
        return row;
    }

    private HBox qcSpaceRow(boolean self) {
        Label icon = new Label();
        icon.setGraphic(Icons.of("globe", 18));
        icon.getStyleClass().add("option-icon");
        Label title = new Label(self ? "我的 QC 空间" : "他的 QC 空间");
        title.getStyleClass().add("option-title");
        Label hint = new Label(self ? "发布说说 · 看看动态" : "看看 TA 的说说");
        hint.getStyleClass().add("option-value");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Label arrow = new Label("›");
        arrow.getStyleClass().add("option-arrow");

        HBox row = new HBox(10, icon, title, spacer, hint, arrow);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("option-row");
        row.setPadding(new Insets(14, 20, 14, 20));
        row.setCursor(javafx.scene.Cursor.HAND);
        row.setOnMouseClicked(e -> {
            Window owner = getScene() == null ? null : getScene().getWindow();
            MomentSpaceView.show(owner, ctx, String.valueOf(profile.qcId()), profile.displayName(), self);
        });
        return row;
    }

    private HBox bottomActions(boolean self) {
        Button message = new Button("发消息");
        message.getStyleClass().add("primary-button");
        message.setDisable(self);
        message.setOnAction(e -> {
            if (onMessageUser != null) {
                onMessageUser.accept(profile.id());
            }
            closeAction.run();
        });

        Button call = new Button("语音通话");
        call.getStyleClass().add("secondary-button");
        call.setDisable(self);
        call.setOnAction(e -> CallDialog.outgoing(getScene() == null ? null : getScene().getWindow(),
                ctx, profile.id(), profile.displayName()));

        Button gift = new Button("送礼物");
        gift.getStyleClass().add("secondary-button");
        gift.setOnAction(e -> {
            Conversation conversation = ctx.store().find(ChatType.PRIVATE, profile.id());
            if (conversation == null) {
                Toast.show(gift, "先加对方为好友才能送礼");
            } else {
                GiftDialog.show(getScene() == null ? null : getScene().getWindow(), ctx, conversation, gift);
            }
        });

        HBox row = new HBox(10);
        row.setAlignment(Pos.CENTER);
        row.setPadding(new Insets(6, 20, 6, 20));
        if (self) {
            Button edit = new Button("编辑资料");
            edit.getStyleClass().add("secondary-button");
            edit.setOnAction(e -> openEditor());
            row.getChildren().addAll(call, gift, edit);
        } else {
            // 别人的主页：可以给对方设置备注（昵称），只影响自己的显示
            Button remark = new Button("设置备注");
            remark.getStyleClass().add("secondary-button");
            remark.setOnAction(e -> openRemark());
            row.getChildren().addAll(call, gift, message, remark);
        }
        return row;
    }

    /** 给好友设置备注（昵称） */
    private void openRemark() {
        javafx.stage.Window owner = getScene() == null ? null : getScene().getWindow();
        // 初值应该是“当前备注”，而不是对方昵称：否则点开直接确定就把昵称写成了备注
        String currentRemark = "";
        io.quack.client.store.Conversation conversation = ctx.store().find(
                io.quack.common.model.ChatType.PRIVATE, profile.id());
        if (conversation != null && conversation.getTitle() != null
                && !conversation.getTitle().equals(profile.displayName())) {
            currentRemark = conversation.getTitle();
        }
        TextInputDialog.show(owner, "设置备注", "留空则显示对方用户名/昵称",
                currentRemark, text -> ctx.setFriendRemark(profile.id(), text)
                        .whenComplete((friend, error) -> Platform.runLater(() -> {
                            if (error != null) {
                                Toast.show(this, "设置失败：" + ClientErrors.describe(error));
                            } else {
                                Toast.show(this, "备注已更新");
                            }
                        })));
    }

    private void openEditor() {
        ModalShell shell = new ModalShell(getScene() == null ? null : getScene().getWindow(), "编辑资料", 520, 720);
        javafx.scene.control.ScrollPane scroll =
                new javafx.scene.control.ScrollPane(new ProfileEditor(ctx, profile, updated -> {
                    this.profile = updated;
                    render();
                    shell.close();
                }));
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("edge-to-edge");
        shell.setContent(scroll);
        shell.showAndWait();
    }

    private VBox placeholder(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("empty-state");
        VBox box = new VBox(label);
        box.setAlignment(Pos.CENTER);
        box.setPadding(new Insets(80, 20, 20, 20));
        return box;
    }

    // ------------------------------------------------------------------
    // 展示用的小工具
    // ------------------------------------------------------------------

    private static String safe(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String genderIcon(String gender) {
        if ("男".equals(gender)) {
            return "♂";
        }
        if ("女".equals(gender)) {
            return "♀";
        }
        return "○";
    }

    private static String ageText(String birthday) {
        LocalDate date = parse(birthday);
        if (date == null) {
            return "年龄未知";
        }
        return Period.between(date, LocalDate.now()).getYears() + " 岁";
    }

    private static String shortDate(String birthday) {
        LocalDate date = parse(birthday);
        return date == null ? "生日未填" : date.format(DateTimeFormatter.ofPattern("M 月 d 日"));
    }

    private static String constellation(String birthday) {
        LocalDate date = parse(birthday);
        if (date == null) {
            return "星座未知";
        }
        int month = date.getMonthValue();
        int day = date.getDayOfMonth();
        String[] signs = {"摩羯座", "水瓶座", "双鱼座", "白羊座", "金牛座", "双子座", "巨蟹座",
                "狮子座", "处女座", "天秤座", "天蝎座", "射手座", "摩羯座"};
        int[] starts = {20, 19, 21, 20, 21, 22, 23, 23, 23, 24, 23, 22};
        int index = day < starts[month - 1] ? month - 1 : month;
        return signs[index];
    }

    private static LocalDate parse(String birthday) {
        if (birthday == null || birthday.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(birthday.trim());
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /** 直接打开「个人信息」编辑页（设置里用） */
    public static void showEditor(Window owner, AppContext ctx, UserProfile profile) {
        ModalShell shell = new ModalShell(owner, "个人信息", 470, 760);
        shell.setContent(new ProfileEditor(ctx, profile, updated -> {
            ctx.store().renameMe(updated.displayName());
            shell.close();
        }));
        shell.showAndWait();
    }

    /** 编辑资料的弹窗内容 */
    private static final class ProfileEditor extends VBox {

        ProfileEditor(AppContext ctx, UserProfile profile, Consumer<UserProfile> onSaved) {
            setSpacing(12);
            setPadding(new Insets(18));
            getStyleClass().add("modal-body");

            TextField qc = new TextField(String.valueOf(profile.qcId()));
            qc.setDisable(true);
            TextField username = new TextField(profile.username());

            TextField signature = new TextField(profile.signature());
            TextField gender = new TextField(profile.gender());
            TextField birthday = new TextField(profile.birthday());
            TextField region = new TextField(profile.region());
            TextField identity = new TextField(profile.identity());

            signature.getStyleClass().add("login-field");
            gender.getStyleClass().add("login-field");
            birthday.getStyleClass().add("login-field");
            region.getStyleClass().add("login-field");
            identity.getStyleClass().add("login-field");

            Label hint = new Label("生日格式 yyyy-MM-dd，例如 2003-04-08");
            hint.getStyleClass().add("modal-hint");

            Button save = new Button("保存");
            save.getStyleClass().add("primary-button");
            save.setMaxWidth(Double.MAX_VALUE);

            // 头像：预览 + 上传
            Label avatarPreview = new Label(Avatars.initial(profile.displayName()));
            avatarPreview.getStyleClass().add("avatar");
            avatarPreview.setMinSize(56, 56);
            avatarPreview.setPrefSize(56, 56);
            avatarPreview.setMaxSize(56, 56);
            Avatars.apply(avatarPreview, profile.id(), profile.avatar());
            String[] avatarUrl = {profile.avatar()};

            Button changeAvatar = new Button("更换头像…");
            changeAvatar.getStyleClass().add("secondary-button");
            changeAvatar.setOnAction(e -> {
                javafx.stage.FileChooser chooser = new javafx.stage.FileChooser();
                chooser.setTitle("选择头像");
                chooser.getExtensionFilters().add(new javafx.stage.FileChooser.ExtensionFilter(
                        "图片", "*.png", "*.jpg", "*.jpeg", "*.gif", "*.webp"));
                java.io.File file = chooser.showOpenDialog(getScene() == null ? null : getScene().getWindow());
                if (file == null) {
                    return;
                }
                changeAvatar.setDisable(true);
                changeAvatar.setText("上传中…");
                ctx.uploadFile(io.quack.client.media.ImageShrinker.square(file.toPath(), 256))
                        .whenComplete((uploaded, error) -> Platform.runLater(() -> {
                            changeAvatar.setDisable(false);
                            changeAvatar.setText("更换头像…");
                            if (error != null) {
                                hint.setText("头像上传失败：" + ClientErrors.describe(error));
                            } else {
                                avatarUrl[0] = uploaded.url();
                                Avatars.apply(avatarPreview, profile.id(), uploaded.url());
                                hint.setText("头像已上传，点保存生效");
                            }
                        }));
            });
            HBox avatarRow = new HBox(12, avatarPreview,
                    new VBox(6, changeAvatar, new Label("支持 png / jpg，建议正方形")));
            avatarRow.setAlignment(Pos.CENTER_LEFT);


            getChildren().addAll(
                    avatarRow,
                    field("QC 号（系统分配，不可修改）", qc),
                    field("用户名（显示给别人的名字）", username),
                    field("个性签名", signature),
                    field("性别（男 / 女 / 保密）", gender), field("生日", birthday),
                    field("所在地", region), field("身份", identity),
                    hint, save);

            save.setOnAction(e -> {
                save.setDisable(true);
                save.setText("保存中…");
                ctx.updateProfile(new io.quack.common.dto.UpdateProfileReq(
                                username.getText(), null, avatarUrl[0],
                                signature.getText(), gender.getText(),
                                birthday.getText(), region.getText(), identity.getText()))
                        .whenComplete((updated, error) -> Platform.runLater(() -> {
                            save.setDisable(false);
                            save.setText("保存");
                            if (error != null) {
                                hint.setText("保存失败：" + ClientErrors.describe(error));
                            } else {
                                onSaved.accept(updated);
                            }
                        }));
            });
        }

        private VBox field(String labelText, TextField input) {
            Label label = new Label(labelText);
            label.getStyleClass().add("field-label");
            input.setMaxWidth(Double.MAX_VALUE);
            return new VBox(5, label, input);
        }
    }
}
