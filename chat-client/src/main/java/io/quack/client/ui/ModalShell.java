package io.quack.client.ui;

import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.input.KeyCode;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.stage.Modality;
import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.util.Duration;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.stage.Window;

/**
 * 统一的模态弹窗外壳。
 *
 * <p>为什么要自己写：JavaFX 自带的 {@code Dialog} 是系统默认外观，跟应用的无边框圆角风格完全不搭，
 * 之前「添加好友」弹窗就是这么露馅的。这里复用主窗口那套标题栏 + 圆角 + 投影，
 * 保证每一个界面长得一致。
 *
 * <p>能力：拖动、ESC 关闭、点关闭按钮、模态阻塞；内容区由调用方填。
 */
public final class ModalShell {

    /** 没有透明边距，缩放热区与可见边框对齐 */
    private static final double SHADOW = 0;

    private final Stage stage = new Stage();
    private final StackPane sceneRoot = new StackPane();
    private final StackPane frame = new StackPane();
    private final StackPane contentHost = new StackPane();
    private final Rectangle clip = new Rectangle();


    /** 当前打开的非模态资料窗口数量：用来阻止主窗口在资料窗口开着时被关掉 */
    private static int nonModalOpen;

    public static boolean hasNonModalOpen() {
        return nonModalOpen > 0;
    }

    private final boolean modal;

    /** 最大化状态与还原前的窗口位置 */
    private boolean maximized;
    private final double[] restoreBounds = new double[4];

    public ModalShell(Window owner, String title, double width, double height) {
        this(owner, title, width, height, true);
    }

    /**
     * @param modal true = 模态（锁住主窗口）；资料类窗口传 false，主窗口仍可移动
     */
    public ModalShell(Window owner, String title, double width, double height, boolean modal) {
        this.modal = modal;
        stage.initOwner(owner);
        // 永不锁定上一级窗口：资料/空间/播放等窗口都可以和主窗口同时操作、自由拖动
        stage.initModality(Modality.NONE);
        stage.initStyle(StageStyle.TRANSPARENT);
        stage.setResizable(true);
        stage.setMinWidth(360);
        stage.setMinHeight(240);

        TitleBar titleBar = new TitleBar(title);
        // 每个窗口都提供最小化 / 最大化 / 关闭
        titleBar.setMaximizeVisible(true);
        titleBar.setOnClose(this::close);
        titleBar.setOnMinimize(() -> stage.setIconified(true));
        titleBar.setOnMaximize(this::toggleMaximize);

        // 拖动 + 八向缩放统一由 WindowChrome 装配（在 scene 创建之后）

        contentHost.getStyleClass().add("content-host");
        VBox.setVgrow(contentHost, Priority.ALWAYS);

        VBox shell = new VBox(titleBar, contentHost);
        shell.getStyleClass().add("window-shell");

        frame.getStyleClass().add("window-frame");
        frame.getChildren().add(shell);
        clip.setArcWidth(28);
        clip.setArcHeight(28);
        clip.widthProperty().bind(shell.widthProperty());
        clip.heightProperty().bind(shell.heightProperty());
        shell.setClip(clip);

        sceneRoot.getStyleClass().add("window-root");
        sceneRoot.setPadding(Insets.EMPTY);
        sceneRoot.getChildren().add(frame);

        Scene scene = new Scene(sceneRoot, width, height);
        // 同主窗口：保留 1/255 不透明度，否则四角收不到鼠标事件
        scene.setFill(Color.rgb(255, 255, 255, 1.0 / 255));
        scene.getStylesheets().add(getClass().getResource("/app.css").toExternalForm());
        Theme.apply(scene);
        scene.setOnKeyPressed(event -> {
            if (event.getCode() == KeyCode.ESCAPE) {
                close();
            }
        });
        stage.setScene(scene);
        stage.setTitle(title);
        // 所有弹窗也支持拖动 + 八向缩放，和主窗口行为一致
        WindowChrome.install(stage, titleBar, frame, () -> maximized, this::toggleMaximize);
        stage.centerOnScreen();
    }

    public javafx.scene.Parent root() {
        return sceneRoot;
    }

    /** 最大化 / 还原（和主窗口一致：铺满当前屏幕，去掉圆角） */
    public void toggleMaximize() {
        if (maximized) {
            stage.setX(restoreBounds[0]);
            stage.setY(restoreBounds[1]);
            stage.setWidth(restoreBounds[2]);
            stage.setHeight(restoreBounds[3]);
            maximized = false;
            clip.setArcWidth(28);
            clip.setArcHeight(28);
        } else {
            restoreBounds[0] = stage.getX();
            restoreBounds[1] = stage.getY();
            restoreBounds[2] = stage.getWidth();
            restoreBounds[3] = stage.getHeight();
            javafx.geometry.Rectangle2D bounds = javafx.stage.Screen
                    .getScreensForRectangle(stage.getX() + stage.getWidth() / 2,
                            stage.getY() + stage.getHeight() / 2, 1, 1).stream()
                    .findFirst()
                    .orElse(javafx.stage.Screen.getPrimary())
                    .getVisualBounds();
            stage.setX(bounds.getMinX());
            stage.setY(bounds.getMinY());
            stage.setWidth(bounds.getWidth());
            stage.setHeight(bounds.getHeight());
            maximized = true;
            clip.setArcWidth(0);
            clip.setArcHeight(0);
        }
    }

    public void setContent(javafx.scene.Parent content) {
        contentHost.getChildren().setAll(content);
        // 最小尺寸跟着内容走：窗口再小也不能把底部按钮裁掉
        if (content != null) {
            double minWidth = Math.max(360, content.minWidth(-1) + 24);
            double minHeight = Math.max(240, content.minHeight(-1) + 44 + 12);
            stage.setMinWidth(minWidth);
            stage.setMinHeight(minHeight);
            if (stage.getWidth() < minWidth) {
                stage.setWidth(minWidth);
            }
            if (stage.getHeight() < minHeight) {
                stage.setHeight(minHeight);
            }
        }
    }

    /**
     * 显示弹窗。
     *
     * <p><b>不碰窗口透明度</b>：窗口必须一开始就是可见的。
     * 之前用 opacity 做淡入，一旦动画没跑起来就会留下"看不见但锁住主窗口"的模态框，
     * 表现为整个应用卡死。现在只对内容做轻微位移 + 淡入，窗口本身永远可见。
     */
    /** 把弹窗放到某个控件上方（贴住按钮，而不是屏幕居中） */
    public void showAbove(javafx.scene.Node anchor) {
        if (anchor == null || anchor.getScene() == null) {
            showAndWait();
            return;
        }
        showAndWait();
        javafx.application.Platform.runLater(() -> {
            var bounds = anchor.localToScreen(anchor.getBoundsInLocal());
            if (bounds == null) {
                return;
            }
            double width = stage.getWidth();
            double height = stage.getHeight();
            double x = bounds.getMinX() + bounds.getWidth() / 2 - width / 2;
            double y = bounds.getMinY() - height - 10;
            var screen = javafx.stage.Screen.getScreensForRectangle(
                            bounds.getMinX(), bounds.getMinY(), 1, 1).stream()
                    .findFirst().orElse(javafx.stage.Screen.getPrimary())
                    .getVisualBounds();
            // 上方放不下就翻到下方
            if (y < screen.getMinY() + 8) {
                y = Math.min(bounds.getMaxY() + 10, screen.getMaxY() - height - 8);
            }
            x = Math.max(screen.getMinX() + 8, Math.min(x, screen.getMaxX() - width - 8));
            stage.setX(x);
            stage.setY(y);
        });
    }

    public void showAndWait() {
        contentHost.setOpacity(1);
        contentHost.setTranslateY(0);
        stage.setOpacity(1);
        stage.show();
        stage.toFront();
        if (!modal) {
            nonModalOpen++;
            stage.setOnHidden(event -> nonModalOpen = Math.max(0, nonModalOpen - 1));
        }
        // 材质到场：只动内容层，窗口本身永远可见（不会留下"看不见却挡路"的模态层）
        io.quack.client.ui.motion.Motion.settle(contentHost);
        io.quack.client.ui.motion.Motion.materialize(contentHost, 8);
    }

    /**
     * 关闭：内容层快速缩回并淡出（150ms），随后真正关窗。
     * 动画期间窗口始终可见，且不影响上层操作，意外中断也不会留下不可见窗口。
     */
    public void close() {
        stage.setOpacity(1);
        if (!stage.isShowing() || io.quack.client.ui.motion.Motion.isReduced()) {
            stage.close();
            return;
        }
        io.quack.client.ui.motion.Motion.dismiss(contentHost, stage::close);
    }

    public Stage stage() {
        return stage;
    }
}
