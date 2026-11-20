package io.quack.client.ui.motion;

import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.layout.StackPane;
import javafx.util.Duration;

/**
 * 界面动效的统一入口：出现、消失、切换、按压反馈。
 *
 * <p>所有动画都走 {@link Spring}——可从当前屏幕值出发、可被下一次交互打断。
 * 这里刻意只做四类运动，避免"到处都在动"：
 * <ol>
 *   <li><b>材质到场（materialize）</b>：弹窗/浮层从 97% 缩放 + 透明淡入，像一块真实的材质落下来；</li>
 *   <li><b>内容切换（swap）</b>：登录→主界面、聊天→通讯录，新旧内容短促交叉淡入；</li>
 *   <li><b>按压反馈（pressable）</b>：按下即缩、松手弹回，反馈发生在 pointer-down 而不是 click；</li>
 *   <li><b>结果提示（pop）</b>：只有"成功/失败"这类值得注意的瞬间才允许一点点回弹。</li>
 * </ol>
 *
 * <p>「减少动态效果」开启后全部退化为即时状态切换——保留反馈，去掉前庭刺激。
 */
public final class Motion {

    private static volatile boolean reduced;

    private Motion() {
    }

    public static void setReduced(boolean value) {
        reduced = value;
    }

    public static boolean isReduced() {
        return reduced;
    }

    // ------------------------------------------------------------------
    // 基础
    // ------------------------------------------------------------------

    public static void to(javafx.beans.property.DoubleProperty property, double target,
                          double velocity, double response, double damping) {
        if (property == null) {
            return;
        }
        if (reduced) {
            property.set(target);
            return;
        }
        Spring.to(property, target, velocity, response, damping);
    }

    /** 从当前值弹到 target（无初始速度） */
    public static void to(javafx.beans.property.DoubleProperty property, double target) {
        to(property, target, 0, Spring.DEFAULT_RESPONSE, Spring.DEFAULT_DAMPING);
    }

    // ------------------------------------------------------------------
    // 材质到场 / 离场
    // ------------------------------------------------------------------

    /**
     * 让一块内容像"材质"一样出现：缩放 0.97 → 1，透明度 0 → 1，并沿出现方向轻微位移。
     *
     * @param node 要出现的内容节点
     * @param dy   起始纵向偏移（正 = 从下方浮起）
     */
    public static void materialize(Node node, double dy) {
        if (node == null) {
            return;
        }
        if (reduced) {
            node.setOpacity(1);
            node.setScaleX(1);
            node.setScaleY(1);
            node.setTranslateY(0);
            return;
        }
        node.setOpacity(0);
        node.setScaleX(0.97);
        node.setScaleY(0.97);
        node.setTranslateY(dy);

        to(node.opacityProperty(), 1, 0, 0.20, 1.0);
        to(node.scaleXProperty(), 1, 0, 0.30, 1.0);
        to(node.scaleYProperty(), 1, 0, 0.30, 1.0);
        to(node.translateYProperty(), 0, 0, 0.34, 1.0);
    }

    public static void materialize(Node node) {
        materialize(node, 8);
    }

    /** 反向：缩回 98% + 淡出，结束后执行 after */
    public static void dismiss(Node node, Runnable after) {
        if (node == null) {
            if (after != null) {
                after.run();
            }
            return;
        }
        if (reduced) {
            if (after != null) {
                after.run();
            }
            return;
        }
        to(node.opacityProperty(), 0, 0, 0.14, 1.0);
        to(node.scaleXProperty(), 0.98, 0, 0.18, 1.0);
        to(node.scaleYProperty(), 0.98, 0, 0.18, 1.0);
        to(node.translateYProperty(), -4, 0, 0.20, 1.0);
        if (after != null) {
            PauseTransition wait = new PauseTransition(Duration.millis(150));
            wait.setOnFinished(event -> after.run());
            wait.play();
        }
    }

    /** 只淡出 */
    public static void fadeOut(Node node, Runnable after) {
        if (node == null) {
            if (after != null) {
                after.run();
            }
            return;
        }
        if (reduced) {
            node.setOpacity(0);
            if (after != null) {
                after.run();
            }
            return;
        }
        to(node.opacityProperty(), 0, 0, 0.16, 1.0);
        if (after != null) {
            PauseTransition wait = new PauseTransition(Duration.millis(170));
            wait.setOnFinished(event -> after.run());
            wait.play();
        }
    }

    // ------------------------------------------------------------------
    // 内容切换
    // ------------------------------------------------------------------

    /**
     * 容器内容交叉淡入：新内容从下方 6px 浮起，旧内容轻微上移淡出。
     *
     * <p>切换期间旧内容不可点，避免"点到了正在离场的界面"。
     */
    public static void swap(StackPane host, Node next) {
        if (host == null || next == null) {
            return;
        }
        if (reduced) {
            host.getChildren().setAll(next);
            return;
        }
        java.util.List<Node> existing = new java.util.ArrayList<>(host.getChildren());
        boolean alreadyThere = existing.contains(next);
        if (alreadyThere) {
            // 快速来回切换：目标内容已经在容器里，直接提到最上层并把别的淡出，
            // 否则会出现"点了聊天却还停在通讯录"的错层
            Spring.stop(next.opacityProperty());
            Spring.stop(next.translateYProperty());
            next.toFront();
            next.setMouseTransparent(false);
            next.setOpacity(1);
            next.setTranslateY(0);
            for (Node other : existing) {
                if (other != next) {
                    fadeOutAndRemove(host, other);
                }
            }
            return;
        }
        for (Node other : existing) {
            fadeOutAndRemove(host, other);
        }
        next.setOpacity(0);
        next.setTranslateY(6);
        next.setMouseTransparent(false);
        host.getChildren().add(next);
        to(next.opacityProperty(), 1, 0, 0.22, 1.0);
        to(next.translateYProperty(), 0, 0, 0.32, 1.0);
    }

    /** 旧内容轻微上移淡出，动画结束后从容器移除 */
    private static void fadeOutAndRemove(StackPane host, Node old) {
        old.setMouseTransparent(true);
        to(old.opacityProperty(), 0, 0, 0.16, 1.0);
        to(old.translateYProperty(), -4, 0, 0.20, 1.0);
        PauseTransition wait = new PauseTransition(Duration.millis(260));
        wait.setOnFinished(event -> {
            // 期间如果又被切回来（opacity 已恢复），这个过期任务不能把当前内容删掉
            if (old.getOpacity() <= 0.02 && old.isMouseTransparent()) {
                host.getChildren().remove(old);
            }
        });
        wait.play();
    }

    // ------------------------------------------------------------------
    // 按压反馈
    // ------------------------------------------------------------------

    /**
     * 给控件装上"按下即缩、松手弹回"的反馈。
     *
     * <p>反馈发生在 pointer-down（用户按下的那一帧），不是 click 之后。
     */
    public static void pressable(Node node) {
        pressable(node, 0.965);
    }

    public static void pressable(Node node, double pressedScale) {
        if (node == null) {
            return;
        }
        node.setOnMousePressed(event -> scaleTo(node, pressedScale, 0.20, 1.0));
        node.setOnMouseReleased(event -> scaleTo(node, 1.0, 0.30, 1.0));
        node.setOnMouseExited(event -> scaleTo(node, 1.0, 0.30, 1.0));
    }

    private static void scaleTo(Node node, double target, double response, double damping) {
        to(node.scaleXProperty(), target, 0, response, damping);
        to(node.scaleYProperty(), target, 0, response, damping);
    }

    /** 成功/失败这类值得注意的瞬间：轻微过冲 */
    public static void pop(Node node) {
        if (node == null || reduced) {
            return;
        }
        node.setScaleX(0.92);
        node.setScaleY(0.92);
        to(node.scaleXProperty(), 1, 0, 0.34, Spring.MOMENTUM_DAMPING);
        to(node.scaleYProperty(), 1, 0, 0.34, Spring.MOMENTUM_DAMPING);
    }

    /** 立即把节点恢复到"静止可见"状态（窗口重新显示、复用 cell 时用） */
    public static void settle(javafx.scene.Node node) {
        if (node == null) {
            return;
        }
        Spring.stop(node.opacityProperty());
        Spring.stop(node.scaleXProperty());
        Spring.stop(node.scaleYProperty());
        Spring.stop(node.translateYProperty());
        node.setOpacity(1);
        node.setScaleX(1);
        node.setScaleY(1);
        node.setTranslateY(0);
    }

    /** 在 FX 线程上安全执行 */
    public static void fx(Runnable action) {
        if (Platform.isFxApplicationThread()) {
            action.run();
        } else {
            Platform.runLater(action);
        }
    }
}
