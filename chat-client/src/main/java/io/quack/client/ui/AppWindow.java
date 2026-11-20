package io.quack.client.ui;

import javafx.animation.FadeTransition;
import javafx.application.Platform;
import javafx.geometry.Bounds;
import javafx.geometry.Insets;
import javafx.geometry.Rectangle2D;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.image.Image;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.util.Duration;

import java.io.InputStream;

/**
 * 无边框窗口外壳：自绘标题栏 + 八向拖拽缩放 + 最大化/还原 + 圆角阴影 + 位置记忆。
 *
 * <p>实现要点：
 * <ul>
 *   <li>用 {@link StageStyle#TRANSPARENT} 去掉系统边框，才能做出圆角和投影；</li>
 *   <li>系统边框没了，缩放要自己算：在窗口四条边/四个角各留 6px 热区，
 *       按下时记住起始屏幕坐标与窗口矩形，拖动时按差值重算（不是累加，避免抖动）；</li>
 *   <li>最大化是“手动”的：铺满当前屏幕的可视区域（避开任务栏），并保存还原尺寸；</li>
 *   <li>窗口位置/大小写进 {@link WindowState}，下次启动还原。</li>
 * </ul>
 */
public class AppWindow {

    /** 四周留给投影的透明边距 */
    /** 无透明边距：缩放热区必须和可见边框重合 */
    private static final double SHADOW = 0;
    /** 边缘缩放热区宽度 */
    private static final double EDGE = 6;

    private enum Zone {
        NONE, N, S, E, W, NE, NW, SE, SW
    }

    private final Stage stage;
    private final StackPane sceneRoot = new StackPane();
    private final StackPane frame = new StackPane();
    private final Rectangle frameClip = new Rectangle();
    private final VBox shell = new VBox();
    private final StackPane contentHost = new StackPane();
    private final TitleBar titleBar;
    private final boolean maximizable;

    private boolean maximized;
    private double restoreX;
    private double restoreY;
    private double restoreWidth;
    private double restoreHeight;

    private Zone zone = Zone.NONE;
    private boolean resizing;
    private double pressScreenX;
    private double pressScreenY;
    private double startX;
    private double startY;
    private double startWidth;
    private double startHeight;


    public AppWindow(Stage stage, String title, double width, double height, boolean maximizable) {
        this.stage = stage;
        this.maximizable = maximizable;
        stage.initStyle(StageStyle.TRANSPARENT);
        stage.setResizable(true);
        stage.setMinWidth(400);
        stage.setMinHeight(520);

        titleBar = new TitleBar(title);
        // 不能直接 stage.close()：那相当于 hide()，不会触发 setOnCloseRequest，
        // 于是“资料窗口还开着时不能关主窗”的保护会被绕过。
        titleBar.setOnClose(this::close);
        titleBar.setOnMinimize(() -> stage.setIconified(true));
        titleBar.setOnMaximize(this::toggleMaximize);
        titleBar.setMaximizeVisible(maximizable);

        contentHost.getStyleClass().add("content-host");
        VBox.setVgrow(contentHost, Priority.ALWAYS);

        shell.getStyleClass().add("window-shell");
        shell.getChildren().addAll(titleBar, contentHost);

        frame.getStyleClass().add("window-frame");
        frame.getChildren().add(shell);
        // 内容可能是不透明的（侧栏、列表），必须裁圆角，否则四角会盖住圆角外框。
        // 注意裁的是 shell 而不是 frame：frame 一旦被裁，四角就收不到鼠标事件，
        // 八向缩放的「角」也就失效了。
        frameClip.setArcWidth(28);
        frameClip.setArcHeight(28);
        frameClip.widthProperty().bind(shell.widthProperty());
        frameClip.heightProperty().bind(shell.heightProperty());
        shell.setClip(frameClip);

        sceneRoot.getStyleClass().add("window-root");
        // 不再留透明边距：窗口可视边界 = scene 边界，缩放热区才能对准
        sceneRoot.setPadding(Insets.EMPTY);
        sceneRoot.getChildren().add(frame);

        Scene scene = new Scene(sceneRoot, width, height);
        // 圆角处的像素不能是「完全透明」：Windows 分层窗口会把 alpha=0 的像素
        // 当作穿透区域，鼠标事件根本到不了 JavaFX，四个角就没法缩放。
        // 给 1/255 的不透明度，肉眼看不出来，但系统会正常命中。
        scene.setFill(Color.rgb(255, 255, 255, 1.0 / 255));
        scene.getStylesheets().add(getClass().getResource("/app.css").toExternalForm());
        // 主题在场景建立后立刻套用：浅色 / 深色 / 跟随系统
        Theme.apply(scene);
        stage.setScene(scene);
        stage.setTitle(title);
        // 窗口最小可压缩尺寸：小于这个值，侧栏 + 聊天区就挤不下了
        stage.setMinWidth(720);
        stage.setMinHeight(520);

        InputStream icon = getClass().getResourceAsStream("/icon.png");
        if (icon != null) {
            stage.getIcons().add(new Image(icon, 64, 64, true, true));
        }

        WindowChrome.install(stage, titleBar, frame, () -> maximized, this::toggleMaximize);
        installState();

        stage.setOnCloseRequest(event -> {
            // 资料/聊天信息窗口开着时，主窗口不能关（但可以拖动）
            if (ModalShell.hasNonModalOpen()) {
                event.consume();
                Toast.show(frame, "请先关闭资料窗口");
                return;
            }
            saveState();
        });
    }

    public Parent root() {
        return sceneRoot;
    }

    public Stage stage() {
        return stage;
    }

    public void show() {
        stage.show();
        if (restoreMaximizedFlag) {
            applyMaximize();
        }
    }

    public void setTitle(String title) {
        stage.setTitle(title);
        titleBar.setTitle(title);
    }

    /** 关闭主窗口：有非模态资料窗口时先提示，并保证状态落盘 */
    private void close() {
        if (ModalShell.hasNonModalOpen()) {
            Toast.show(frame, "请先关闭资料窗口");
            return;
        }
        saveState();
        stage.close();
    }

    /** 换内容（登录 -&gt; 主界面），带一个很短的淡入，避免生硬闪切 */
    public void setContent(Parent content) {
        setContent(content, true);
    }

    public void setContent(Parent content, boolean animate) {
        if (!animate) {
            contentHost.getChildren().setAll(content);
            io.quack.client.ui.motion.Motion.settle(content);
            return;
        }
        // 登录 → 主界面：新旧内容交叉淡入，新内容从下方 8px 浮起
        io.quack.client.ui.motion.Motion.swap(contentHost, content);
    }

    /** 登录页不要标题栏：把自绘标题栏整条隐藏，同时去掉窗口外框（渐变本身就是窗口） */
    public void setTitleBarVisible(boolean visible) {
        titleBar.setVisible(visible);
        titleBar.setManaged(visible);
        if (visible) {
            frame.getStyleClass().remove("login-shell");
        } else if (!frame.getStyleClass().contains("login-shell")) {
            frame.getStyleClass().add("login-shell");
        }
    }

    /** 登录页用小窗口，登录后放大 */
    public void resizeTo(double width, double height) {
        stage.setWidth(width);
        stage.setHeight(height);
    }

    public void setMaximizable(boolean value) {
        titleBar.setMaximizeVisible(value);
    }

    public void setMinSize(double width, double height) {
        stage.setMinWidth(width);
        stage.setMinHeight(height);
    }

    /** 登录窗口比较小，登录成功后放大到聊天尺寸；已经有更大窗口就不动 */
    public void ensureAtLeast(double width, double height) {
        Rectangle2D bounds = currentScreenBounds();
        // 不能大过屏幕可用区域，否则底部输入区会被屏幕边缘裁掉
        double newWidth = Math.min(Math.max(stage.getWidth(), width), bounds.getWidth());
        double newHeight = Math.min(Math.max(stage.getHeight(), height), bounds.getHeight());
        if (newWidth == stage.getWidth() && newHeight == stage.getHeight()) {
            return;
        }
        stage.setWidth(newWidth);
        stage.setHeight(newHeight);
        if (stage.getX() + newWidth > bounds.getMaxX()) {
            stage.setX(Math.max(bounds.getMinX(), bounds.getMaxX() - newWidth));
        }
        if (stage.getY() + newHeight > bounds.getMaxY()) {
            stage.setY(Math.max(bounds.getMinY(), bounds.getMaxY() - newHeight));
        }
    }

    public void toggleMaximize() {
        // 是否显示最大化按钮由 maximizable 控制；这里允许显式调用（登录页的 □ 也要能用）
        if (maximized) {
            restoreFromMaximize();
        } else {
            applyMaximize();
        }
    }

    // ------------------------------------------------------------------
    // 最大化 / 还原
    // ------------------------------------------------------------------

    private void applyMaximize() {
        if (maximized) {
            return;
        }
        restoreX = stage.getX();
        restoreY = stage.getY();
        restoreWidth = stage.getWidth();
        restoreHeight = stage.getHeight();

        Rectangle2D bounds = currentScreenBounds();
        stage.setX(bounds.getMinX());
        stage.setY(bounds.getMinY());
        stage.setWidth(bounds.getWidth());
        stage.setHeight(bounds.getHeight());

        maximized = true;
        sceneRoot.setPadding(Insets.EMPTY);
        frameClip.setArcWidth(0);
        frameClip.setArcHeight(0);
        frame.getStyleClass().add("maximized");
        titleBar.setMaximized(true);
    }

    private void restoreFromMaximize() {
        if (!maximized) {
            return;
        }
        stage.setWidth(restoreWidth);
        stage.setHeight(restoreHeight);
        stage.setX(restoreX);
        stage.setY(restoreY);

        maximized = false;
        sceneRoot.setPadding(new Insets(SHADOW));
        frameClip.setArcWidth(28);
        frameClip.setArcHeight(28);
        frame.getStyleClass().remove("maximized");
        titleBar.setMaximized(false);
    }

    private Rectangle2D currentScreenBounds() {
        double x = stage.getX() + stage.getWidth() / 2;
        double y = stage.getY() + stage.getHeight() / 2;
        return Screen.getScreensForRectangle(x, y, 1, 1).stream()
                .findFirst()
                .orElse(Screen.getPrimary())
                .getVisualBounds();
    }

    // ------------------------------------------------------------------
    // 拖动窗口（只在标题栏空白处）
    // ------------------------------------------------------------------


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

    // ------------------------------------------------------------------
    // 位置记忆
    // ------------------------------------------------------------------

    private boolean restoreMaximizedFlag;

    private void installState() {
        restoreMaximizedFlag = WindowState.restore("main", stage, 420, 560);
    }

    private void saveState() {
        WindowState.save("main", stage, maximized, restoreX, restoreY, restoreWidth, restoreHeight);
    }

    /** 供测试或“退出登录”使用：立刻把当前窗口状态落盘 */
    public void persistState() {
        saveState();
    }

    /** 延迟到界面就绪后再保存一次（最大化状态在窗口显示后才可靠） */
    public void persistStateLater() {
        Platform.runLater(this::saveState);
    }
}
