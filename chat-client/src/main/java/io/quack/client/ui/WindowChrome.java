package io.quack.client.ui;

import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.stage.Stage;

import java.util.function.BooleanSupplier;

/**
 * 窗口外壳的唯一入口：<b>拖动 + 八向缩放 + 双击最大化</b>。
 *
 * <p>所有窗口（主窗口、每一个弹窗）都通过这里装配，保证行为一致；
 * 以后加新窗口只要调用一次 {@code WindowChrome.install(...)}。
 */
public final class WindowChrome {

    private WindowChrome() {
    }

    /**
     * @param stage         目标窗口
     * @param titleBar      拖动区域（可为 null，表示不可拖动）
     * @param frame         窗口可视边界节点，缩放热区以它的 bounds 为准
     * @param maximized     是否已最大化
     * @param toggleMaximize 双击标题栏要执行的动作（可为 null）
     */
    public static void install(Stage stage, Node titleBar, Node frame,
                               BooleanSupplier maximized, Runnable toggleMaximize) {
        BooleanSupplier isMaximized = maximized == null ? () -> false : maximized;

        if (titleBar != null) {
            WindowDrag.install(titleBar, stage);
            if (toggleMaximize != null) {
                titleBar.addEventHandler(MouseEvent.MOUSE_CLICKED, event -> {
                    if (event.getClickCount() == 2 && event.getButton() == MouseButton.PRIMARY
                            && !isButton(event.getTarget())) {
                        toggleMaximize.run();
                    }
                });
            }
        }

        ChromeResize.install(stage, isMaximized, frame);
    }

    /** 不可拖动、不可最大化的窗口（比如某些浮层） */
    public static void install(Stage stage, Node frame) {
        install(stage, null, frame, () -> false, null);
    }

    private static boolean isButton(Object target) {
        Node node = target instanceof Node ? (Node) target : null;
        while (node != null) {
            if (node instanceof Button) {
                return true;
            }
            node = node.getParent();
        }
        return false;
    }
}
