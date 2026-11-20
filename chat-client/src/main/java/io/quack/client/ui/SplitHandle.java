package io.quack.client.ui;

import javafx.scene.Cursor;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.Region;

import java.util.function.DoubleSupplier;

/**
 * 模块之间的可拖动分割线。
 *
 * <p>拖动改变目标面板的尺寸，并受最小/最大值约束（最小值由调用方给，避免被拖没）。
 * 双击恢复默认尺寸。
 *
 * <p><b>方向约定</b>（和两个实际用法一致）：
 * <ul>
 *   <li>{@link Axis#HORIZONTAL}：分割线在目标<b>右侧</b>（左栏 | 线 | 内容），
 *       鼠标往右拖 = 目标变宽；</li>
 *   <li>{@link Axis#VERTICAL}：分割线在目标<b>上方</b>（消息区 / 线 / 输入区），
 *       鼠标往<b>上</b>拖 = 目标变高，所以位移要取反。</li>
 * </ul>
 */
public final class SplitHandle extends Region {

    public enum Axis {
        /** 拖动改变宽度 */
        HORIZONTAL,
        /** 拖动改变高度 */
        VERTICAL
    }

    private final Axis axis;
    private final Region target;
    private final double min;
    private final DoubleSupplier max;
    private final double initial;

    private double startPointer;
    private double startSize;

    public SplitHandle(Axis axis, Region target, double min, DoubleSupplier max, double initial) {
        this.axis = axis;
        this.target = target;
        this.min = min;
        this.max = max;
        this.initial = initial;

        setMinSize(axis == Axis.HORIZONTAL ? 5 : 1, axis == Axis.HORIZONTAL ? 1 : 5);
        setPrefSize(axis == Axis.HORIZONTAL ? 5 : Region.USE_COMPUTED_SIZE,
                axis == Axis.HORIZONTAL ? Region.USE_COMPUTED_SIZE : 5);
        if (axis == Axis.HORIZONTAL) {
            setMaxWidth(5);
            setMaxHeight(Double.MAX_VALUE);
            setCursor(Cursor.H_RESIZE);
            getStyleClass().add("split-handle");
        } else {
            setMaxHeight(5);
            setMaxWidth(Double.MAX_VALUE);
            setCursor(Cursor.V_RESIZE);
            getStyleClass().add("split-handle-v");
        }

        addEventHandler(MouseEvent.MOUSE_PRESSED, event -> {
            startPointer = axis == Axis.HORIZONTAL ? event.getScreenX() : event.getScreenY();
            startSize = currentSize();
            event.consume();
        });

        addEventHandler(MouseEvent.MOUSE_DRAGGED, event -> {
            double pointer = axis == Axis.HORIZONTAL ? event.getScreenX() : event.getScreenY();
            // 垂直分割线在目标上方：往上拖 = 变大，所以位移取反
            double delta = pointer - startPointer;
            double wanted = startSize + (axis == Axis.HORIZONTAL ? delta : -delta);
            apply(Math.max(min, Math.min(max.getAsDouble(), wanted)));
            event.consume();
        });

        addEventHandler(MouseEvent.MOUSE_CLICKED, event -> {
            if (event.getClickCount() == 2) {
                apply(initial);
            }
        });
    }

    /** 目标当前的尺寸（读 pref，而不是实际值：拖拽期间实际值可能被父容器压缩） */
    private double currentSize() {
        double size = axis == Axis.HORIZONTAL ? target.getPrefWidth() : target.getPrefHeight();
        if (size <= 0 || Double.isNaN(size)) {
            size = axis == Axis.HORIZONTAL ? target.getWidth() : target.getHeight();
        }
        return size;
    }

    /** 直接设置到某个尺寸（供外部在窗口变化时夹取） */
    public void apply(double size) {
        if (axis == Axis.HORIZONTAL) {
            target.setMinWidth(size);
            target.setPrefWidth(size);
            target.setMaxWidth(size);
        } else {
            target.setMinHeight(size);
            target.setPrefHeight(size);
            target.setMaxHeight(size);
        }
    }

    /**
     * 窗口/容器变化时由外部调用：把目标尺寸夹回 [min, 当前可用上限]。
     * 之前没有这一步，窗口缩小后输入区会顶出窗口，底部按钮被裁掉一半。
     */
    public void clamp(double availableMax) {
        double upper = Math.min(Math.max(min, availableMax), Math.max(min, max.getAsDouble()));
        double current = currentSize();
        if (current > upper) {
            apply(upper);
        } else if (current < min) {
            apply(min);
        }
    }

    public Axis axis() {
        return axis;
    }

    public double min() {
        return min;
    }

    public double initial() {
        return initial;
    }
}
