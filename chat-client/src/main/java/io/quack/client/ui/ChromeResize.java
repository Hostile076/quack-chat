package io.quack.client.ui;

import javafx.animation.AnimationTimer;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.geometry.Bounds;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.stage.Screen;
import javafx.stage.Stage;

import java.util.function.BooleanSupplier;

/**
 * 无边框窗口的八向拖拽缩放。
 *
 * <p><b>这次修掉的真问题</b>：窗口原来外面留了 10~12px 透明边距（早期做投影用的），
 * 而边缘检测是按 scene 尺寸算的 —— 热区落在可见窗口**外面**，所以拖可见边框毫无反应。
 * 现在统一按 frame 节点的可视 bounds 判断，并且去掉那圈透明留白。
 *
 * <p>另外三点：
 * <ol>
 *   <li>鼠标事件只记录目标尺寸，由 {@link AnimationTimer} 每帧提交一次，避免每个
 *       DRAGGED 事件都触发布局；</li>
 *   <li>用屏幕坐标算增量，避免窗口尺寸变化时 scene 坐标缩放带来的抖动；</li>
 *   <li>限制在屏幕可视区域内，不会把窗口拖到屏幕外面。</li>
 * </ol>
 */
public final class ChromeResize {

    private static final double MARGIN = 6;
    private static final BooleanProperty RESIZING = new SimpleBooleanProperty(false);

    private ChromeResize() {
    }

    public static ReadOnlyBooleanProperty resizingProperty() {
        return RESIZING;
    }

    public static boolean isResizing() {
        return RESIZING.get();
    }

    /** 供窗口外壳统一调用 */
    public static void install(Stage stage, BooleanSupplier maximized, Node frame) {
        Scene scene = stage.getScene();
        if (scene == null || frame == null) {
            return;
        }
        new Resizer(stage, frame, maximized).install(scene);
    }

    // ------------------------------------------------------------------

    private enum Edge {
        NONE, TOP, BOTTOM, LEFT, RIGHT, TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT
    }

    private static final class Resizer {

        private final Stage stage;
        private final Node frame;
        private final BooleanSupplier maximized;

        private Scene scene;
        private Edge edge = Edge.NONE;
        private boolean active;
        private AnimationTimer timer;

        private double startScreenX;
        private double startScreenY;
        private double startX;
        private double startY;
        private double startWidth;
        private double startHeight;

        private double targetX;
        private double targetY;
        private double targetWidth;
        private double targetHeight;

        Resizer(Stage stage, Node frame, BooleanSupplier maximized) {
            this.stage = stage;
            this.frame = frame;
            this.maximized = maximized;
        }

        void install(Scene scene) {
            this.scene = scene;
            scene.addEventFilter(MouseEvent.MOUSE_MOVED, this::onMoved);
            scene.addEventFilter(MouseEvent.MOUSE_EXITED, event -> {
                if (!active) {
                    scene.setCursor(Cursor.DEFAULT);
                    edge = Edge.NONE;
                }
            });
            scene.addEventFilter(MouseEvent.MOUSE_PRESSED, this::onPressed);
            scene.addEventFilter(MouseEvent.MOUSE_DRAGGED, this::onDragged);
            scene.addEventFilter(MouseEvent.MOUSE_RELEASED, this::onReleased);
        }

        private void onMoved(MouseEvent event) {
            if (active) {
                return;
            }
            if (maximized.getAsBoolean()) {
                scene.setCursor(Cursor.DEFAULT);
                return;
            }
            edge = detect(event.getSceneX(), event.getSceneY());
            scene.setCursor(cursorOf(edge));
        }

        private void onPressed(MouseEvent event) {
            if (event.getButton() != MouseButton.PRIMARY || maximized.getAsBoolean()) {
                return;
            }
            Edge pressed = detect(event.getSceneX(), event.getSceneY());
            if (pressed == Edge.NONE) {
                return;
            }
            active = true;
            edge = pressed;
            RESIZING.set(true);

            startScreenX = event.getScreenX();
            startScreenY = event.getScreenY();
            startX = stage.getX();
            startY = stage.getY();
            startWidth = stage.getWidth();
            startHeight = stage.getHeight();

            targetX = startX;
            targetY = startY;
            targetWidth = startWidth;
            targetHeight = startHeight;

            ensureTimer();
            // 边框区域不该把点击传给里面的控件
            event.consume();
        }

        private void onDragged(MouseEvent event) {
            if (!active) {
                return;
            }
            compute(event.getScreenX() - startScreenX, event.getScreenY() - startScreenY);
            event.consume();
        }

        private void onReleased(MouseEvent event) {
            if (!active) {
                return;
            }
            active = false;
            apply();
            RESIZING.set(false);
            ensureTimer();
            event.consume();
        }

        private void compute(double dx, double dy) {
            double minWidth = Math.max(stage.getMinWidth(), 320);
            double minHeight = Math.max(stage.getMinHeight(), 220);
            javafx.geometry.Rectangle2D screen = screenBounds();

            double x = startX;
            double y = startY;
            double width = startWidth;
            double height = startHeight;

            boolean left = edge == Edge.LEFT || edge == Edge.TOP_LEFT || edge == Edge.BOTTOM_LEFT;
            boolean right = edge == Edge.RIGHT || edge == Edge.TOP_RIGHT || edge == Edge.BOTTOM_RIGHT;
            boolean top = edge == Edge.TOP || edge == Edge.TOP_LEFT || edge == Edge.TOP_RIGHT;
            boolean bottom = edge == Edge.BOTTOM || edge == Edge.BOTTOM_LEFT || edge == Edge.BOTTOM_RIGHT;

            if (right) {
                width = clamp(startWidth + dx, minWidth, screen.getMaxX() - startX);
            }
            if (left) {
                width = clamp(startWidth - dx, minWidth, startX + startWidth - screen.getMinX());
                x = startX + (startWidth - width);
            }
            if (bottom) {
                height = clamp(startHeight + dy, minHeight, screen.getMaxY() - startY);
            }
            if (top) {
                height = clamp(startHeight - dy, minHeight, startY + startHeight - screen.getMinY());
                y = startY + (startHeight - height);
            }

            targetX = x;
            targetY = y;
            targetWidth = width;
            targetHeight = height;
        }

        private void ensureTimer() {
            if (timer != null) {
                return;
            }
            timer = new AnimationTimer() {
                @Override
                public void handle(long now) {
                    apply();
                    if (!active && !changed()) {
                        stop();
                        timer = null;
                    }
                }
            };
            timer.start();
        }

        private boolean changed() {
            return Math.abs(stage.getX() - targetX) > 0.5
                    || Math.abs(stage.getY() - targetY) > 0.5
                    || Math.abs(stage.getWidth() - targetWidth) > 0.5
                    || Math.abs(stage.getHeight() - targetHeight) > 0.5;
        }

        private void apply() {
            if (targetWidth <= 0 || targetHeight <= 0) {
                return;
            }
            if (Math.abs(stage.getX() - targetX) > 0.5) {
                stage.setX(targetX);
            }
            if (Math.abs(stage.getY() - targetY) > 0.5) {
                stage.setY(targetY);
            }
            if (Math.abs(stage.getWidth() - targetWidth) > 0.5) {
                stage.setWidth(targetWidth);
            }
            if (Math.abs(stage.getHeight() - targetHeight) > 0.5) {
                stage.setHeight(targetHeight);
            }
        }

        private javafx.geometry.Rectangle2D screenBounds() {
            double centerX = stage.getX() + stage.getWidth() / 2;
            double centerY = stage.getY() + stage.getHeight() / 2;
            return Screen.getScreensForRectangle(centerX, centerY, 1, 1).stream()
                    .findFirst()
                    .orElse(Screen.getPrimary())
                    .getVisualBounds();
        }

        /** 命中检测：以 frame 的可视边界为准，而不是 scene */
        private Edge detect(double sceneX, double sceneY) {
            Bounds bounds = frame.localToScene(frame.getBoundsInLocal());
            double left = sceneX - bounds.getMinX();
            double right = bounds.getMaxX() - sceneX;
            double top = sceneY - bounds.getMinY();
            double bottom = bounds.getMaxY() - sceneY;

            if (left < 0 || right < 0 || top < 0 || bottom < 0) {
                return Edge.NONE;
            }
            boolean nearLeft = left <= MARGIN;
            boolean nearRight = right <= MARGIN;
            boolean nearTop = top <= MARGIN;
            boolean nearBottom = bottom <= MARGIN;

            if (nearTop && nearLeft) {
                return Edge.TOP_LEFT;
            }
            if (nearTop && nearRight) {
                return Edge.TOP_RIGHT;
            }
            if (nearBottom && nearLeft) {
                return Edge.BOTTOM_LEFT;
            }
            if (nearBottom && nearRight) {
                return Edge.BOTTOM_RIGHT;
            }
            if (nearTop) {
                return Edge.TOP;
            }
            if (nearBottom) {
                return Edge.BOTTOM;
            }
            if (nearLeft) {
                return Edge.LEFT;
            }
            if (nearRight) {
                return Edge.RIGHT;
            }
            return Edge.NONE;
        }

        private static Cursor cursorOf(Edge edge) {
            return switch (edge) {
                case TOP -> Cursor.N_RESIZE;
                case BOTTOM -> Cursor.S_RESIZE;
                case LEFT -> Cursor.W_RESIZE;
                case RIGHT -> Cursor.E_RESIZE;
                case TOP_LEFT -> Cursor.NW_RESIZE;
                case TOP_RIGHT -> Cursor.NE_RESIZE;
                case BOTTOM_LEFT -> Cursor.SW_RESIZE;
                case BOTTOM_RIGHT -> Cursor.SE_RESIZE;
                case NONE -> Cursor.DEFAULT;
            };
        }

        private static double clamp(double value, double min, double max) {
            return Math.max(min, Math.min(max, value));
        }
    }
}
