package io.quack.client.ui;

import io.quack.client.ui.motion.Motion;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.scene.Cursor;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.shape.Circle;

/**
 * 开关控件（iOS 语汇）。
 *
 * <p>JavaFX 没有现成的 Switch，CheckBox 改样式很别扭，不如自己画一个：
 * 一条圆角轨道 + 一个滑块。滑块用弹簧走位（带一点惯性回弹），
 * 而不是固定时长的位移动画——这样连点也能被自然接住。
 */
public class ToggleSwitch extends StackPane {

    private static final double TRACK_WIDTH = 44;
    private static final double TRACK_HEIGHT = 24;

    private final Region track = new Region();
    private final Circle thumb = new Circle(9);
    private final BooleanProperty selected = new SimpleBooleanProperty(false);

    public ToggleSwitch(boolean initial) {
        setPrefSize(TRACK_WIDTH, TRACK_HEIGHT);
        setMaxSize(TRACK_WIDTH, TRACK_HEIGHT);
        setMinSize(TRACK_WIDTH, TRACK_HEIGHT);
        setCursor(Cursor.HAND);

        track.getStyleClass().add("switch-track");
        track.setPrefSize(TRACK_WIDTH, TRACK_HEIGHT);
        track.setMaxSize(TRACK_WIDTH, TRACK_HEIGHT);
        track.setMinSize(TRACK_WIDTH, TRACK_HEIGHT);

        thumb.getStyleClass().add("switch-thumb");
        thumb.setMouseTransparent(true);

        getChildren().addAll(track, thumb);
        StackPane.setAlignment(thumb, javafx.geometry.Pos.CENTER_LEFT);

        selected.set(initial);
        applyState(false);

        selected.addListener((obs, old, value) -> applyState(true));
        setOnMouseClicked(e -> selected.set(!selected.get()));
    }

    public BooleanProperty selectedProperty() {
        return selected;
    }

    public boolean isSelected() {
        return selected.get();
    }

    public void setSelected(boolean value) {
        selected.set(value);
    }

    private void applyState(boolean animate) {
        double target = selected.get() ? TRACK_WIDTH - 20 : 4;
        if (selected.get()) {
            if (!track.getStyleClass().contains("on")) {
                track.getStyleClass().add("on");
            }
        } else {
            track.getStyleClass().remove("on");
        }
        if (animate) {
            // 手指拨动的开关带一点惯性：阻尼 0.82、响应 0.22s
            Motion.to(thumb.translateXProperty(), target, 0, 0.22, 0.82);
        } else {
            thumb.setTranslateX(target);
        }
    }
}
