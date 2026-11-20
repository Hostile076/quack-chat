package io.quack.client.ui;

import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.stage.Stage;

/**
 * 窗口拖动（移动）。
 *
 * <p>鼠标移动一秒能产生上百个事件，每个事件都调用一次 {@code stage.setX/setY}，
 * 等于让窗口管理器做上百次重排；这里合并到「每帧最多一次」，
 * 拖动时窗口跟手很多，CPU 也低。
 */
public final class WindowDrag {

    private WindowDrag() {
    }

    /**
     * 交互控件（按钮、输入框、下拉框）不应该触发拖动：
     * 否则在输入框里选文字会把窗口拖走。
     */
    private static boolean isInteractiveTarget(Object target) {
        javafx.scene.Node node = target instanceof javafx.scene.Node ? (javafx.scene.Node) target : null;
        while (node != null) {
            if (node instanceof javafx.scene.control.ButtonBase
                    || node instanceof javafx.scene.control.TextInputControl
                    || node instanceof javafx.scene.control.ComboBoxBase
                    || node instanceof javafx.scene.control.ScrollBar) {
                return true;
            }
            for (String style : node.getStyleClass()) {
                if (style.startsWith("title-button") || style.startsWith("login-chrome")) {
                    return true;
                }
            }
            node = node.getParent();
        }
        return false;
    }

    public static void install(Node handle, Stage stage) {
        double[] offset = new double[2];
        double[] pending = new double[2];
        boolean[] dragging = {false};
        boolean[] moved = {false};
        boolean[] scheduled = {false};

        handle.addEventHandler(MouseEvent.MOUSE_PRESSED, event -> {
            if (event.getButton() != MouseButton.PRIMARY) {
                return;
            }
            // 点在窗口按钮上时不要启动拖动，否则按钮会和拖拽抢事件
            if (isInteractiveTarget(event.getTarget())) {
                dragging[0] = false;
                return;
            }
            offset[0] = event.getScreenX() - stage.getX();
            offset[1] = event.getScreenY() - stage.getY();
            pending[0] = stage.getX();
            pending[1] = stage.getY();
            moved[0] = false;
            dragging[0] = true;
        });

        handle.addEventHandler(MouseEvent.MOUSE_DRAGGED, event -> {
            if (!dragging[0]) {
                return;
            }
            pending[0] = Math.round(event.getScreenX() - offset[0]);
            pending[1] = Math.round(event.getScreenY() - offset[1]);
            moved[0] = true;

            if (!scheduled[0]) {
                scheduled[0] = true;
                Platform.runLater(() -> {
                    scheduled[0] = false;
                    // 一次调用里同时设置 X/Y，避免窗口管理器按两次重排处理
                    if (Math.abs(stage.getX() - pending[0]) > 0.5
                            || Math.abs(stage.getY() - pending[1]) > 0.5) {
                        stage.setX(pending[0]);
                        stage.setY(pending[1]);
                    }
                });
            }
            event.consume();
        });

        handle.addEventHandler(MouseEvent.MOUSE_RELEASED, event -> {
            if (!dragging[0]) {
                return;
            }
            dragging[0] = false;
            // 没有真正拖动（只是点了一下按钮）就不要再挪窗口
            if (moved[0]) {
                stage.setX(pending[0]);
                stage.setY(pending[1]);
            }
        });
    }
}
