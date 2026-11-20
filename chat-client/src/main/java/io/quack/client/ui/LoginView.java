package io.quack.client.ui;

import io.quack.client.AppContext;
import io.quack.client.ClientErrors;
import io.quack.client.store.AccountStore;
import io.quack.common.dto.LoginReq;
import io.quack.common.model.User;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.util.StringConverter;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.io.InputStream;
import java.util.concurrent.CompletableFuture;

/**
 * 登录 / 注册界面。
 *
 * <p>登录：账号可以填 <b>QC 号</b>（推荐，唯一）或用户名（仅当不重名时）。
 *
 * <p>注册：只允许填<b>用户名和密码</b>。QC 号是系统分配的，界面上不给编辑入口；
 * 注册成功会弹窗把分配到的 QC 号告诉用户 —— 以后换设备登录就靠它。
 */
public class LoginView extends StackPane {

    private final AppContext ctx;
    private final TextField serverField = new TextField("ws://127.0.0.1:8080/ws");
    private final TextField accountField = new TextField();
    private final PasswordField passwordField = new PasswordField();
    private final Label accountLabel = new Label("账号（QC 号或用户名）");
    private final Button submitButton = new Button("登 录");
    private final Button offlineButton = new Button("离线登录（查看本地记录）");
    private final ComboBox<AccountStore.SavedAccount> accountPicker = new ComboBox<>();
    private final VBox accountPickerBox;
    private final Hyperlink toggleLink = new Hyperlink("还没有账号？注册一个");
    private final Hyperlink serverLink = new Hyperlink("选择服务器 >");
    private final Label serverLabel = new Label();
    private final Label statusLabel = new Label(" ");
    private final Label hintLabel = new Label();

    private boolean registerMode;

    public LoginView(AppContext ctx) {
        this(ctx, null, null);
    }

    public LoginView(AppContext ctx, String prefillAccount, String serverUrl) {
        this.ctx = ctx;
        getStyleClass().add("login-root");

        // 默认服务器：优先用调用方指定的，否则保持上一次使用的地址
        String initialServer = serverUrl != null && !serverUrl.isBlank()
                ? serverUrl : ctx.accounts().lastServerUrl();
        serverField.setText(initialServer);
        if (prefillAccount != null && !prefillAccount.isBlank() && !"0".equals(prefillAccount)) {
            accountField.setText(prefillAccount);
        }

        VBox card = new VBox(12);
        card.getStyleClass().add("login-card");
        // 卡片浮在壁纸上：留出四周留白，才看得出"一块材质"
        card.setMaxWidth(352);
        card.setMinSize(0, 0);
        card.setMaxHeight(javafx.scene.layout.Region.USE_PREF_SIZE);
        card.setFillWidth(true);
        card.setPadding(new Insets(10, 22, 16, 22));

        // 登录页没有标题栏，但窗口按钮直接复用标题栏那一套（同图标、同尺寸、同悬停效果），放在右上角
        javafx.scene.control.Button minimize =
                TitleBar.createWindowButton("M3,9 H13", "title-button");
        minimize.setOnAction(e -> ctx.window().stage().setIconified(true));

        javafx.scene.control.Button maximize =
                TitleBar.createWindowButton("M3,3 H13 V13 H3 Z", "title-button");
        maximize.setOnAction(e -> ctx.window().toggleMaximize());

        javafx.scene.control.Button close =
                TitleBar.createWindowButton("M3.5,3.5 L12.5,12.5 M12.5,3.5 L3.5,12.5", "title-button-close");
        close.setOnAction(e -> ctx.window().stage().close());

        HBox chrome = new HBox(10, minimize, maximize, close);
        chrome.setAlignment(Pos.CENTER_RIGHT);
        chrome.setPickOnBounds(false);
        // 不让 StackPane 把它拉满整窗，否则按钮会跑到垂直中间
        chrome.setMaxSize(javafx.scene.layout.Region.USE_PREF_SIZE,
                javafx.scene.layout.Region.USE_PREF_SIZE);
        if (ctx.window() != null && ctx.window().stage() != null) {
            // 拖动装在整窗根节点上：没有标题栏时，点空白处（包括顶部空白带）都能拖
            setPickOnBounds(true);
            WindowDrag.install(this, ctx.window().stage());
        }

        accountLabel.getStyleClass().add("field-label");

        // 本机账号选择：选中后自动填 QC 号，并提示是否可离线登录
        accountPicker.getItems().setAll(ctx.accounts().accounts());
        Popups.install(accountPicker);
        accountPicker.setMaxWidth(Double.MAX_VALUE);
        accountPicker.setPromptText("选择这台设备登录过的账号");
        accountPicker.setConverter(new StringConverter<>() {
            @Override
            public String toString(AccountStore.SavedAccount account) {
                return account == null ? "" : account.display();
            }

            @Override
            public AccountStore.SavedAccount fromString(String text) {
                return null;
            }
        });
        accountPicker.setOnAction(event -> {
            AccountStore.SavedAccount selected = accountPicker.getValue();
            if (selected == null) {
                return;
            }
            accountField.setText(String.valueOf(selected.qcId()));
            passwordField.clear();
            statusLabel.getStyleClass().remove("error");
            statusLabel.setText(selected.canLoginOffline()
                    ? "本机账号：可在线登录，也可离线查看记录"
                    : "该账号还没有在本机在线登录过，暂不能离线登录");
            passwordField.requestFocus();
        });
        Label pickerLabel = new Label("本机账号（离线登录用）");
        pickerLabel.getStyleClass().add("field-label");
        accountPickerBox = new VBox(6, pickerLabel, accountPicker);
        boolean hasAccounts = !accountPicker.getItems().isEmpty();
        accountPickerBox.setVisible(hasAccounts);
        accountPickerBox.setManaged(hasAccounts);

        // 预填账号时同步选中本机账号
        if (prefillAccount != null && !prefillAccount.isBlank()) {
            for (AccountStore.SavedAccount saved : accountPicker.getItems()) {
                if (String.valueOf(saved.qcId()).equals(prefillAccount)
                        || saved.username().equalsIgnoreCase(prefillAccount)) {
                    accountPicker.setValue(saved);
                    break;
                }
            }
        }

        card.getChildren().addAll(
                brand(),
                divider(),
                accountPickerBox,
                labelField(accountLabel, accountField, "例如 10001"),
                field("密码", passwordField, "至少 6 位"),
                serverRow());

        serverLink.getStyleClass().add("link-button");
        serverLink.setOnAction(e -> openServerDialog());
        updateServerLabel();

        submitButton.getStyleClass().add("primary-button");
        submitButton.setMaxWidth(Double.MAX_VALUE);
        submitButton.setOnAction(e -> submit());

        offlineButton.getStyleClass().add("secondary-button");
        offlineButton.setMaxWidth(Double.MAX_VALUE);
        offlineButton.setOnAction(e -> offlineLogin());

        toggleLink.getStyleClass().add("link-button");
        toggleLink.setOnAction(e -> setRegisterMode(!registerMode));

        HBox linkRow = new HBox(toggleLink);
        linkRow.setAlignment(Pos.CENTER);

        statusLabel.getStyleClass().add("login-status");
        statusLabel.setWrapText(true);
        statusLabel.setMinHeight(18);

        hintLabel.getStyleClass().add("login-hint");
        hintLabel.setWrapText(true);

        VBox bottom = new VBox(6, linkRow, statusLabel, hintLabel);
        bottom.setAlignment(Pos.CENTER);
        card.getChildren().addAll(submitButton, offlineButton, bottom);

        setMinSize(0, 0);
        // 窗口按钮贴在"方框"（窗口）的右上角，不跟着居中的表单列走
        StackPane.setAlignment(chrome, Pos.TOP_RIGHT);
        StackPane.setMargin(chrome, new Insets(8, 6, 0, 0));
        StackPane.setMargin(card, new Insets(12));
        getChildren().addAll(card, chrome);
        setAlignment(card, Pos.CENTER);
        setPadding(Insets.EMPTY);

        passwordField.setOnAction(e -> submit());
        setRegisterMode(false);
    }



    private HBox brand() {
        ImageView logo = new ImageView();
        try (InputStream in = LoginView.class.getResourceAsStream("/icon.png")) {
            if (in != null) {
                logo.setImage(new Image(in, 44, 44, true, true));
            }
        } catch (Exception ignored) {
            // 图标缺失不影响登录
        }
        logo.setFitWidth(44);
        logo.setFitHeight(44);
        logo.setSmooth(true);

        Label title = new Label("Quack Chat");
        title.getStyleClass().add("login-title");
        Label subtitle = new Label("桌面即时通讯 · 私聊 / 群聊 / 好友申请");
        subtitle.getStyleClass().add("login-subtitle");

        VBox texts = new VBox(2, title, subtitle);
        HBox row = new HBox(12, logo, texts);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    private VBox field(String labelText, TextField field, String prompt) {
        Label label = new Label(labelText);
        label.getStyleClass().add("field-label");
        return labelField(label, field, prompt);
    }

    private VBox labelField(Label label, TextField field, String prompt) {
        field.setPromptText(prompt);
        field.getStyleClass().add("login-field");
        field.setMaxWidth(Double.MAX_VALUE);
        return new VBox(6, label, field);
    }

    private StackPane divider() {
        StackPane line = new StackPane();
        line.getStyleClass().add("login-divider");
        line.setPrefHeight(1);
        line.setMinHeight(1);
        return line;
    }

    /** 供截图脚本直接打开注册态 */
    public void showRegisterMode() {
        setRegisterMode(true);
    }

    private void setRegisterMode(boolean enabled) {
        this.registerMode = enabled;
        submitButton.setText(enabled ? "注 册 并 登 录" : "登 录");
        toggleLink.setText(enabled ? "已有账号？返回登录" : "还没有账号？注册一个");
        accountLabel.setText(enabled ? "用户名" : "账号（QC 号或用户名）");
        accountField.setPromptText(enabled ? "3~20 位字母/数字/下划线，可重名" : "例如 10001");
        boolean hasAccounts = !accountPicker.getItems().isEmpty();
        accountPickerBox.setVisible(!enabled && hasAccounts);
        accountPickerBox.setManaged(!enabled && hasAccounts);
        offlineButton.setVisible(!enabled);
        offlineButton.setManaged(!enabled);
        if (enabled) {
            hintLabel.setText("QC 号由系统自动分配，注册成功后请记好；用户名可以重名");
            hintLabel.setVisible(true);
            hintLabel.setManaged(true);
        } else {
            // 登录页不显示演示账号信息，也不预填账号/密码
            hintLabel.setText("");
            hintLabel.setVisible(false);
            hintLabel.setManaged(false);
        }
        statusLabel.getStyleClass().remove("error");
        statusLabel.setText(" ");
        if (enabled) {
            accountField.clear();
            passwordField.clear();
        }
        accountField.requestFocus();
    }

    private void offlineLogin() {
        String account = accountField.getText() == null ? "" : accountField.getText().trim();
        String password = passwordField.getText() == null ? "" : passwordField.getText();
        AccountStore.SavedAccount selected = accountPicker.getValue();
        if (selected == null && !account.isEmpty()) {
            for (AccountStore.SavedAccount saved : ctx.accounts().accounts()) {
                if (String.valueOf(saved.qcId()).equals(account)
                        || saved.username().equalsIgnoreCase(account)) {
                    selected = saved;
                    break;
                }
            }
        }
        if (selected == null) {
            showError("请先选择本机账号，或在线登录一次后再离线登录");
            return;
        }
        if (password.isEmpty()) {
            showError("离线登录也要输入密码（用于本机校验）");
            return;
        }
        offlineButton.setDisable(true);
        submitButton.setDisable(true);
        statusLabel.getStyleClass().remove("error");
        statusLabel.setText("正在读取本地记录…");
        final long qcId = selected.qcId();
        ctx.loginOffline(qcId, password).whenComplete((ignored, error) -> Platform.runLater(() -> {
            offlineButton.setDisable(false);
            submitButton.setDisable(false);
            if (error != null) {
                showError("离线登录失败：" + ClientErrors.describe(error));
            } else {
                statusLabel.setText("已进入离线模式");
            }
        }));
    }

    private HBox serverRow() {
        serverLabel.getStyleClass().add("login-hint");
        serverLabel.setWrapText(true);
        HBox row = new HBox(8, serverLink, serverLabel);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    private void updateServerLabel() {
        serverLabel.setText("当前：" + serverField.getText());
    }

    /** 服务器地址放到子界面，默认保持上一次使用的地址 */
    private void openServerDialog() {
        ModalShell dialog = new ModalShell(getScene() == null ? null : getScene().getWindow(),
                "选择服务器", 540, 280);
        Label title = new Label("服务器地址");
        title.getStyleClass().add("profile-name");
        TextField input = new TextField(serverField.getText());
        input.setPromptText("ws://127.0.0.1:8080/ws 或 wss://your.domain/ws");
        input.getStyleClass().add("search-field");
        Label error = new Label();
        error.getStyleClass().add("error");
        error.setWrapText(true);
        Button cancel = new Button("取消");
        cancel.getStyleClass().add("secondary-button");
        cancel.setOnAction(e -> dialog.close());
        Button save = new Button("保存");
        save.getStyleClass().add("primary-button");
        save.setOnAction(e -> {
            String value = input.getText() == null ? "" : input.getText().trim();
            String normalized = normalizeServer(value);
            if (normalized == null) {
                error.setText("请填写 ws:// 或 wss:// 开头的服务器地址");
                return;
            }
            serverField.setText(normalized);
            ctx.accounts().rememberServer(normalized);
            updateServerLabel();
            dialog.close();
        });
        input.setOnAction(e -> save.fire());
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox actions = new HBox(8, spacer, cancel, save);
        actions.setAlignment(Pos.CENTER_RIGHT);
        VBox body = new VBox(12, title, input, error, actions);
        body.setPadding(new Insets(14));
        body.getStyleClass().add("modal-body");
        dialog.setContent(body);
        dialog.showAndWait();
        Platform.runLater(input::requestFocus);
    }

    /** http/https 自动转 ws/wss；不合法返回 null */
    private String normalizeServer(String value) {
        if (value == null || value.isBlank() || value.contains(" ")) {
            return null;
        }
        String normalized = value;
        if (normalized.startsWith("http://")) {
            normalized = "ws://" + normalized.substring("http://".length());
        } else if (normalized.startsWith("https://")) {
            normalized = "wss://" + normalized.substring("https://".length());
        }
        if (!normalized.startsWith("ws://") && !normalized.startsWith("wss://")) {
            return null;
        }
        String rest = normalized.substring(normalized.indexOf("://") + 3);
        return rest.isBlank() || rest.startsWith("/") ? null : normalized;
    }

    private void submit() {
        String url = serverField.getText() == null ? "" : serverField.getText().trim();
        String account = accountField.getText() == null ? "" : accountField.getText().trim();
        String password = passwordField.getText() == null ? "" : passwordField.getText();

        if (url.isEmpty() || account.isEmpty() || password.isEmpty()) {
            showError("服务器、账号、密码都不能为空");
            return;
        }
        if (registerMode && password.length() < 6) {
            showError("密码至少 6 位");
            return;
        }

        submitButton.setDisable(true);
        statusLabel.getStyleClass().remove("error");
        statusLabel.setText(registerMode ? "注册中…" : "连接中…");

        if (registerMode) {
            ctx.register(url, account, password).whenComplete((user, error) -> Platform.runLater(() -> {
                submitButton.setDisable(false);
                if (error != null) {
                    showError("注册失败：" + ClientErrors.describe(error));
                } else {
                    statusLabel.setText("注册成功");
                    showQcId(user);
                }
            }));
        } else {
            ctx.login(url, account, password).whenComplete((ignored, error) -> Platform.runLater(() -> {
                submitButton.setDisable(false);
                if (error == null) {
                    statusLabel.setText("登录成功");
                } else {
                    showError("登录失败：" + ClientErrors.describe(error));
                }
            }));
        }
    }

    /** 把系统分配的 QC 号明确告诉用户：这是以后登录的唯一可靠凭证 */
    private void showQcId(User user) {
        ModalShell shell = new ModalShell(getScene() == null ? null : getScene().getWindow(),
                "注册成功", 440, 260);
        Label header = new Label("系统分配的 QC 号：" + user.qcId());
        header.getStyleClass().add("profile-name");
        Label content = new Label("已自动登录。\nQC 号由系统生成、不可修改，换设备登录时用它（用户名可能重名）。");
        content.setWrapText(true);
        content.getStyleClass().add("profile-sub");
        Button ok = new Button("知道了");
        ok.getStyleClass().add("primary-button");
        ok.setOnAction(e -> shell.close());
        VBox body = new VBox(12, header, content, ok);
        body.setPadding(new Insets(16));
        body.getStyleClass().add("modal-body");
        shell.setContent(body);
        shell.showAndWait();
    }

    private void showError(String message) {
        statusLabel.setText(message);
        if (!statusLabel.getStyleClass().contains("error")) {
            statusLabel.getStyleClass().add("error");
        }
    }

    /** 供测试或后续“记住上次账号”使用 */
    public LoginReq currentCredentials() {
        return new LoginReq(accountField.getText(), passwordField.getText());
    }
}
