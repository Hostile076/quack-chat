package io.quack.client.ui;

import io.quack.client.ui.motion.Motion;
import javafx.animation.PauseTransition;
import javafx.geometry.Bounds;
import javafx.geometry.Point2D;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.stage.Popup;
import javafx.stage.Screen;
import javafx.stage.Window;
import javafx.util.Duration;

/**
 * 轻量浮层提示。
 *
 * <p>Apple 的做法是「结果出现时才值得动」：提示从 96% 缩放 + 上方 6px 浮起，
 * 停 2 秒后淡出；同时只保留一个提示，新的会顶掉旧的，避免叠成一摞。
 * 提示的样式直接复用 app.css 里的 {@code .toast-*}，浅色/深色都能跟上。
 */
public final class Toast {

    private static Popup current;
    private static PauseTransition currentTimer;

    private Toast() {
    }

    /** 贴在某个控件旁边（用于「请先关闭资料窗口」这类即时提醒） */
    public static void show(Node anchor, String message) {
        if (anchor == null || anchor.getScene() == null) {
            return;
        }
        Bounds bounds = anchor.localToScreen(anchor.getBoundsInLocal());
        if (bounds == null) {
            return;
        }
        Point2D point = new Point2D(bounds.getMinX() + bounds.getWidth() / 2, bounds.getMaxY() + 8);
        present(anchor.getScene().getWindow(), message, false,
                point.getX() - 70, point.getY(), 1800);
    }

    /**
     * 在窗口上方弹出一个结果提示：停留 2 秒，然后渐隐消失。
     *
     * @param success true=成功提示；false=失败提示
     */
    public static void showAboveWindow(Window window, String message, boolean success) {
        if (window == null) {
            return;
        }
        Label probe = createLabel(message, success);
        Popup popup = new Popup();
        popup.getContent().add(probe);
        popup.setAutoHide(false);
        popup.setHideOnEscape(false);
        popup.show(window, 0, 0);
        double width = Math.max(140, probe.getWidth() > 0 ? probe.getWidth() : probe.prefWidth(-1));
        double height = Math.max(30, probe.getHeight() > 0 ? probe.getHeight() : probe.prefHeight(-1));
        double x = window.getX() + (window.getWidth() - width) / 2;
        double y = window.getY() - height - 12;
        javafx.geometry.Rectangle2D screen = Screen.getScreensForRectangle(window.getX(), window.getY(),
                        Math.max(1, window.getWidth()), Math.max(1, window.getHeight()))
                .stream().findFirst().orElse(Screen.getPrimary()).getVisualBounds();
        if (y < screen.getMinY() + 8) {
            // 窗口贴着屏幕顶部时，放到窗口内部上方，避免弹出屏幕
            y = window.getY() + 44;
        }
        popup.setX(x);
        popup.setY(y);
        // 先顶掉旧的（会停掉旧计时器），再给新的排计时器：
        // 反过来会把刚创建的新计时器当成旧的停掉，提示就永远不消失
        replaceCurrent(popup);
        animateIn(probe);
        scheduleHide(popup, probe, 2000);
    }

    /** 贴在锚点下方、随内容宽度自适应（用于控件旁的即时提示） */
    private static void present(Window owner, String message, boolean success,
                                double x, double y, int holdMillis) {
        Window host = owner;
        if (host == null) {
            host = Window.getWindows().stream().filter(Window::isShowing).findFirst().orElse(null);
        }
        if (host == null) {
            return;
        }
        Label label = createLabel(message, success);
        Popup popup = new Popup();
        popup.getContent().add(label);
        popup.setAutoHide(true);
        popup.setHideOnEscape(true);
        popup.show(host, x, y);
        replaceCurrent(popup);
        animateIn(label);
        scheduleHide(popup, label, holdMillis);
    }

    private static Label createLabel(String message, boolean success) {
        Label label = new Label(message);
        label.getStyleClass().addAll("root", success ? "toast-success" : "toast-error");
        if (Theme.isDark()) {
            label.getStyleClass().add("dark");
        }
        // Popup 的 Scene 不继承主窗口样式表，这里手动带上，才能用同一套令牌
        var css = Toast.class.getResource("/app.css");
        if (css != null) {
            label.getStylesheets().add(css.toExternalForm());
        }
        return label;
    }

    private static void animateIn(Label label) {
        label.setOpacity(0);
        label.setScaleX(0.96);
        label.setScaleY(0.96);
        label.setTranslateY(6);
        Motion.to(label.opacityProperty(), 1, 0, 0.18, 1.0);
        Motion.to(label.scaleXProperty(), 1, 0, 0.30, 1.0);
        Motion.to(label.scaleYProperty(), 1, 0, 0.30, 1.0);
        Motion.to(label.translateYProperty(), 0, 0, 0.32, 1.0);
    }

    private static void scheduleHide(Popup popup, Label label, int holdMillis) {
        PauseTransition hold = new PauseTransition(Duration.millis(holdMillis));
        hold.setOnFinished(e -> {
            Motion.to(label.opacityProperty(), 0, 0, 0.16, 1.0);
            Motion.to(label.translateYProperty(), -4, 0, 0.20, 1.0);
            PauseTransition wait = new PauseTransition(Duration.millis(180));
            wait.setOnFinished(done -> popup.hide());
            wait.play();
            if (currentTimer == hold) {
                currentTimer = null;
            }
        });
        hold.play();
        currentTimer = hold;
    }

    private static void replaceCurrent(Popup popup) {
        Popup previous = current;
        PauseTransition previousTimer = currentTimer;
        current = popup;
        if (previous != null && previous.isShowing()) {
            previous.hide();
        }
        if (previousTimer != null) {
            previousTimer.stop();
        }
    }
}
