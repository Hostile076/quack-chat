package io.quack.client.ui;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.shape.SVGPath;

import java.io.InputStream;

/**
 * 自绘标题栏。
 *
 * <p>为什么要自己画：窗口设成 {@code StageStyle.TRANSPARENT} 之后，
 * 系统标题栏和边框都没了，最小化/最大化/关闭、拖动、双击最大化都得自己实现。
 * 好处是样式可以完全贴合应用，圆角、阴影、按钮动效都能做。
 */
public class TitleBar extends HBox {

    private final Label titleLabel = new Label();
    private final Button maximizeButton;
    private final SVGPath maximizeIcon = icon("M3,3 H13 V13 H3 Z");

    private Runnable onClose = () -> {
    };
    private Runnable onMinimize = () -> {
    };
    private Runnable onMaximize = () -> {
    };

    private boolean maximized;

    public TitleBar(String title) {
        getStyleClass().add("title-bar");
        setAlignment(Pos.CENTER_LEFT);
        setSpacing(10);
        setPadding(new Insets(0, 6, 0, 12));
        setMinHeight(44);
        setPrefHeight(44);

        ImageView logo = logo();
        titleLabel.setText(title);
        titleLabel.getStyleClass().add("title-bar-title");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Button minimize = windowButton(icon("M3,9 H13"), "title-button");
        minimize.setOnAction(e -> onMinimize.run());

        maximizeButton = windowButton(maximizeIcon, "title-button");
        maximizeButton.setOnAction(e -> onMaximize.run());

        Button close = windowButton(icon("M3.5,3.5 L12.5,12.5 M12.5,3.5 L3.5,12.5"), "title-button-close");
        close.setOnAction(e -> onClose.run());

        getChildren().addAll(logo, titleLabel, spacer, minimize, maximizeButton, close);
    }

    public void setTitle(String title) {
        titleLabel.setText(title);
    }

    public void setOnClose(Runnable onClose) {
        this.onClose = onClose;
    }

    public void setOnMinimize(Runnable onMinimize) {
        this.onMinimize = onMinimize;
    }

    public void setOnMaximize(Runnable onMaximize) {
        this.onMaximize = onMaximize;
    }

    public void setMaximizeVisible(boolean visible) {
        maximizeButton.setVisible(visible);
        maximizeButton.setManaged(visible);
    }

    /** 切换最大化/还原图标 */
    public void setMaximized(boolean maximized) {
        if (this.maximized == maximized) {
            return;
        }
        this.maximized = maximized;
        maximizeIcon.setContent(maximized
                ? "M4,4 H12 V12 H4 Z M2.5,6.5 V2.5 H10"
                : "M3,3 H13 V13 H3 Z");
    }

    /**
     * 供登录页复用：和主窗口标题栏**完全同尺寸、同图标**的窗口按钮。
     * 登录页没有标题栏，但按钮尺寸/悬停效果必须和主界面一致。
     */
    public static Button createWindowButton(String iconContent, String styleClass) {
        return windowButton(icon(iconContent), styleClass);
    }

    private static Button windowButton(SVGPath graphic, String styleClass) {
        Button button = new Button();
        button.setGraphic(graphic);
        button.getStyleClass().addAll("title-button", styleClass);
        button.setFocusTraversable(false);
        // 按下即缩、松手弹回：反馈发生在 pointer-down，而不是 click 之后
        io.quack.client.ui.motion.Motion.pressable(button, 0.88);
        return button;
    }

    private static SVGPath icon(String content) {
        SVGPath path = new SVGPath();
        path.setContent(content);
        path.getStyleClass().add("title-icon");
        return path;
    }

    private static ImageView logo() {
        ImageView view;
        try (InputStream in = TitleBar.class.getResourceAsStream("/icon.png")) {
            view = in == null ? new ImageView() : new ImageView(new Image(in, 18, 18, true, true));
        } catch (Exception e) {
            view = new ImageView();
        }
        view.setFitWidth(18);
        view.setFitHeight(18);
        view.setPreserveRatio(true);
        view.setSmooth(true);
        return view;
    }
}
