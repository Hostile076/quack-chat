package io.quack.client.ui;

import io.quack.client.AppContext;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.util.function.Consumer;

/**
 * 通用设置：外观（浅色 / 深色 / 跟随系统）、减少动态效果、提示音、界面字号、下载行为。
 *
 * <p>外观与动效是"看一眼就生效"的偏好：点完立即作用于所有窗口，不需要重启。
 */
public final class GeneralSettingsDialog {

    private GeneralSettingsDialog() {
    }

    public static void show(Window owner, AppContext ctx) {
        ModalShell shell = new ModalShell(owner, "通用设置", 460, 640);
        var prefs = ctx.store().prefs();

        VBox content = new VBox(16);
        content.setPadding(new Insets(20));
        content.getStyleClass().add("modal-body");

        Label title = new Label("通用");
        title.getStyleClass().add("profile-name");

        // ---- 外观 ----
        Label appearanceTitle = new Label("外观");
        appearanceTitle.getStyleClass().add("option-title");
        HBox segmented = new HBox(0);
        segmented.getStyleClass().add("segmented");
        segmented.setMaxWidth(Region.USE_PREF_SIZE);
        java.util.List<Button> segments = new java.util.ArrayList<>();
        for (Theme.Mode mode : Theme.Mode.values()) {
            Button button = new Button(mode.label());
            button.getStyleClass().add("segment");
            button.setOnAction(e -> {
                Theme.setMode(mode);
                refreshSegments(segments, Theme.mode());
            });
            segments.add(button);
            segmented.getChildren().add(button);
        }
        refreshSegments(segments, Theme.mode());

        Label appearanceHint = new Label("深色外观会同时应用到主窗口、聊天页与所有弹窗");
        appearanceHint.getStyleClass().add("modal-hint");
        appearanceHint.setWrapText(true);

        // ---- 动态效果 ----
        HBox motionRow = switchRow("减少动态效果", "关闭弹窗/切换的弹性动画，保留必要的淡入淡出",
                Theme.isReduceMotion(), value -> Theme.setReduceMotion(value));

        // ---- 提示音 ----
        HBox soundRow = switchRow("消息提示音", "收到新消息时发出提示音",
                prefs.isNotifySound(), prefs::setNotifySound);

        // ---- 下载后自动打开 ----
        HBox autoOpenRow = switchRow("下载后自动打开", "文件下载完成后用默认程序打开",
                prefs.isAutoOpenDownload(), prefs::setAutoOpenDownload);

        // ---- 显示已隐藏的会话 ----
        HBox hiddenRow = switchRow("显示已隐藏的会话", "在会话列表里显示被“隐藏会话”藏起来的条目",
                prefs.isShowHiddenSessions(), value -> {
                    prefs.setShowHiddenSessions(value);
                    ctx.refreshMainFilter();
                });

        // ---- 字号 ----
        Label sizeTitle = new Label("界面字号");
        sizeTitle.getStyleClass().add("option-title");
        HBox sizeButtons = new HBox(8);
        int[] sizes = {12, 13, 15};
        String[] labels = {"小", "中", "大"};
        for (int i = 0; i < sizes.length; i++) {
            final int size = sizes[i];
            Button button = new Button(labels[i]);
            button.getStyleClass().add(prefs.getFontSize() == size ? "small-primary-button" : "secondary-button");
            button.setOnAction(e -> {
                prefs.setFontSize(size);
                applyFontSize(shell.stage().getScene().getRoot(), size);
                // 主窗口也一起改
                for (Window window : new java.util.ArrayList<>(Window.getWindows())) {
                    if (window instanceof javafx.stage.Stage stage && stage.getScene() != null) {
                        applyFontSize(stage.getScene().getRoot(), size);
                    }
                }
                sizeButtons.getChildren().forEach(node -> node.getStyleClass().removeAll(
                        "small-primary-button", "secondary-button"));
                button.getStyleClass().add("small-primary-button");
            });
            sizeButtons.getChildren().add(button);
        }

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Button close = new Button("完成");
        close.getStyleClass().add("primary-button");
        close.setOnAction(e -> shell.close());
        io.quack.client.ui.motion.Motion.pressable(close, 0.97);
        HBox actions = new HBox(spacer, close);
        actions.setAlignment(Pos.CENTER_RIGHT);

        Label footerHint = new Label("设置立即生效");
        footerHint.getStyleClass().add("modal-hint");

        content.getChildren().addAll(title,
                new VBox(6, appearanceTitle, segmented, appearanceHint),
                motionRow, soundRow, autoOpenRow, hiddenRow,
                new VBox(6, sizeTitle, sizeButtons),
                footerHint,
                actions);
        shell.setContent(content);
        shell.showAndWait();
    }

    private static void refreshSegments(java.util.List<Button> segments, Theme.Mode active) {
        Theme.Mode[] modes = Theme.Mode.values();
        for (int i = 0; i < segments.size(); i++) {
            Button button = segments.get(i);
            button.getStyleClass().remove("segment-active");
            if (modes[i] == active) {
                button.getStyleClass().add("segment-active");
            }
        }
    }

    private static void applyFontSize(javafx.scene.Parent root, int size) {
        if (root != null) {
            root.setStyle("-fx-font-size: " + size + "px;");
        }
    }

    private static HBox switchRow(String title, String subtitle, boolean value, Consumer<Boolean> onChange) {
        Label titleLabel = new Label(title);
        titleLabel.getStyleClass().add("option-title");
        Label subLabel = new Label(subtitle);
        subLabel.getStyleClass().add("option-value");

        VBox texts = new VBox(2, titleLabel, subLabel);
        HBox.setHgrow(texts, Priority.ALWAYS);

        ToggleSwitch toggle = new ToggleSwitch(value);
        toggle.selectedProperty().addListener((obs, old, next) -> onChange.accept(next));

        HBox row = new HBox(12, texts, toggle);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(10, 4, 10, 4));
        return row;
    }
}
