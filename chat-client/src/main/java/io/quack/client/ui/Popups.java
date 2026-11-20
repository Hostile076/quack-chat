package io.quack.client.ui;

import javafx.application.Platform;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Tooltip;
import javafx.stage.PopupWindow;
import javafx.stage.Window;

/**
 * 弹出层的主题适配。
 *
 * <p>JavaFX 的 Tooltip / ContextMenu / ComboBox 下拉都跑在**独立的 Popup 场景**里，
 * 不会自动继承主窗口的样式表和令牌，所以必须显式接管：
 * <ul>
 *   <li>Tooltip 用内联样式（浮层提示在浅色/深色下都是深色半透明，Apple 也是这样）；</li>
 *   <li>ContextMenu 在弹出时把 app.css 和 {@code .root/.dark} 类补到它的场景上，
 *       这样令牌（-c-surface 等）能正常解析；</li>
 *   <li>ComboBox 下拉在弹出后按窗口类型找到它的 Popup 场景，做同样处理。</li>
 * </ul>
 */
public final class Popups {

    /** 浮层提示在两种主题下保持一致：深色半透明，11.5px */
    private static final String TOOLTIP_STYLE =
            "-fx-background-color: rgba(28, 28, 30, 0.94);"
                    + "-fx-text-fill: #ffffff;"
                    + "-fx-background-radius: 8;"
                    + "-fx-padding: 5 10 5 10;"
                    + "-fx-font-size: 11.5px;"
                    + "-fx-effect: dropshadow(gaussian, rgba(0, 0, 0, 0.24), 10, 0.02, 0, 3);";

    private Popups() {
    }

    public static Tooltip tooltip(String text) {
        Tooltip tooltip = new Tooltip(text);
        tooltip.setStyle(TOOLTIP_STYLE);
        tooltip.setShowDelay(javafx.util.Duration.millis(320));
        tooltip.setHideDelay(javafx.util.Duration.millis(80));
        return tooltip;
    }

    /** 右键菜单：弹出时补上样式表与主题类 */
    public static void install(ContextMenu menu) {
        menu.setOnShowing(event -> Platform.runLater(() -> {
            if (menu.getScene() != null) {
                styleScene(menu.getScene());
            }
        }));
    }

    /** 下拉框：弹出后找到内部 Popup 场景并套用主题 */
    public static <T> void install(ComboBox<T> comboBox) {
        comboBox.showingProperty().addListener((obs, old, showing) -> {
            if (!showing) {
                return;
            }
            Platform.runLater(() -> {
                for (Window window : java.util.List.copyOf(Window.getWindows())) {
                    if (window instanceof PopupWindow && window.getScene() != null
                            && window.getScene().getRoot() != null
                            && window.getScene().getRoot().getStyleClass().contains("combo-box-popup")) {
                        styleScene(window.getScene());
                    }
                }
            });
        });
    }

    /** 让一个 Popup 场景拥有令牌与暗色支持 */
    public static void styleScene(Scene scene) {
        if (scene == null) {
            return;
        }
        var css = Popups.class.getResource("/app.css");
        if (css != null) {
            String url = css.toExternalForm();
            if (!scene.getStylesheets().contains(url)) {
                scene.getStylesheets().add(url);
            }
        }
        Parent root = scene.getRoot();
        if (root == null) {
            return;
        }
        if (!root.getStyleClass().contains("root")) {
            root.getStyleClass().add("root");
        }
        if (Theme.isDark()) {
            if (!root.getStyleClass().contains("dark")) {
                root.getStyleClass().add("dark");
            }
        } else {
            root.getStyleClass().remove("dark");
        }
    }
}
