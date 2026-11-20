import io.quack.client.ui.SplitHandle;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * SplitHandle 方向/边界回归（不依赖主界面，避免窗口边缘缩放的干扰）。
 *
 * <p>覆盖：
 * <ul>
 *   <li>垂直分割线在目标上方：往上拖 = 目标变高，往下拖 = 变矮；</li>
 *   <li>水平分割线在目标右侧：往右拖 = 目标变宽，往左拖 = 变窄；</li>
 *   <li>min / max / clamp 都不会越界。</li>
 * </ul>
 */
public class SplitDebug {

    private static int passed;
    private static final List<String> failures = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        CompletableFuture<Void> started = new CompletableFuture<>();
        Platform.startup(() -> started.complete(null));
        started.get(10, TimeUnit.SECONDS);

        CompletableFuture<Void> done = new CompletableFuture<>();
        Platform.runLater(() -> {
            try {
                vertical();
                horizontal();
                clampChecks();
            } catch (Throwable error) {
                failures.add("未捕获: " + error);
                error.printStackTrace();
            } finally {
                System.out.println("\n== 结果 ==");
                System.out.println("PASS " + passed + " / FAIL " + failures.size());
                for (String failure : failures) {
                    System.out.println(" - " + failure);
                }
                done.complete(null);
            }
        });
        done.get(30, TimeUnit.SECONDS);
        Platform.exit();
        System.exit(failures.isEmpty() ? 0 : 1);
    }

    private static void vertical() {
        Region target = new Region();
        target.setMinHeight(108);
        target.setPrefHeight(136);
        SplitHandle handle = new SplitHandle(SplitHandle.Axis.VERTICAL, target, 108, () -> 300, 136);
        show(new VBox(handle, target));
        check("垂直：初始 136", () -> eq(target.getPrefHeight(), 136));
        // 往上拖 60px
        drag(handle, 0, -60);
        check("垂直：往上拖变高", () -> assertThat(target.getPrefHeight() >= 180,
                "实际 " + target.getPrefHeight()));
        // 再往下拖 100px
        drag(handle, 0, 100);
        check("垂直：往下拖变矮", () -> assertThat(target.getPrefHeight() < 180,
                "实际 " + target.getPrefHeight()));
        // 一路往上拖：不超过 max
        drag(handle, 0, -2000);
        check("垂直：不超过 max=300", () -> assertThat(target.getPrefHeight() <= 300.01,
                "实际 " + target.getPrefHeight()));
        // 一路往下拖：停在下限 108
        drag(handle, 0, 2000);
        check("垂直：停在下限 108", () -> eq(target.getPrefHeight(), 108));
    }

    private static void horizontal() {
        Region target = new Region();
        target.setMinWidth(180);
        target.setPrefWidth(200);
        SplitHandle handle = new SplitHandle(SplitHandle.Axis.HORIZONTAL, target, 180, () -> 320, 200);
        javafx.scene.layout.HBox box = new javafx.scene.layout.HBox(target, handle);
        show(box);
        check("水平：初始 200", () -> eq(target.getPrefWidth(), 200));
        drag(handle, 40, 0);
        check("水平：往右拖变宽", () -> assertThat(target.getPrefWidth() >= 235,
                "实际 " + target.getPrefWidth()));
        drag(handle, -100, 0);
        check("水平：往左拖变窄", () -> assertThat(target.getPrefWidth() < 235,
                "实际 " + target.getPrefWidth()));
        drag(handle, -2000, 0);
        check("水平：停在下限 180", () -> eq(target.getPrefWidth(), 180));
        drag(handle, 2000, 0);
        check("水平：不超过 max=320", () -> assertThat(target.getPrefWidth() <= 320.01,
                "实际 " + target.getPrefWidth()));
    }

    private static void clampChecks() {
        Region target = new Region();
        target.setPrefHeight(280);
        SplitHandle handle = new SplitHandle(SplitHandle.Axis.VERTICAL, target, 108, () -> 400, 136);
        show(new VBox(handle, target));
        check("clamp：窗口变矮后夹回可用高度", () -> {
            handle.apply(280);
            handle.clamp(200);
            eq(target.getPrefHeight(), 200);
        });
        check("clamp：不会低于最小值", () -> {
            handle.apply(150);
            handle.clamp(50);
            eq(target.getPrefHeight(), 108);
        });
        check("clamp：本来就合法时不动", () -> {
            handle.apply(200);
            handle.clamp(300);
            eq(target.getPrefHeight(), 200);
        });
    }

    // ------------------------------------------------------------------

    private static void show(javafx.scene.Parent content) {
        Stage stage = new Stage();
        stage.setScene(new Scene(content, 500, 600));
        stage.show();
        content.applyCss();
        content.requestLayout();
        content.layout();
    }

    private static void drag(SplitHandle handle, double deltaX, double deltaY) {
        // x/y 用 -1：窗口边缘缩放过滤器会判定为"不在窗口内"，不会 consume 合成事件
        double screenX = handle.getScene().getWindow().getX() + 100;
        double screenY = handle.getScene().getWindow().getY() + 100;
        handle.fireEvent(mouse(MouseEvent.MOUSE_PRESSED, screenX, screenY));
        handle.fireEvent(mouse(MouseEvent.MOUSE_DRAGGED, screenX + deltaX, screenY + deltaY));
        handle.fireEvent(mouse(MouseEvent.MOUSE_RELEASED, screenX + deltaX, screenY + deltaY));
    }

    private static MouseEvent mouse(javafx.event.EventType<MouseEvent> type, double screenX, double screenY) {
        return new MouseEvent(type, -1, -1, screenX, screenY, MouseButton.PRIMARY, 1,
                false, false, false, false, true, false, false, true, false, false, null);
    }

    private static void check(String name, Check body) {
        try {
            body.run();
            passed++;
            System.out.println("PASS " + name);
        } catch (Throwable error) {
            failures.add(name + " :: " + error.getMessage());
            System.out.println("FAIL " + name + " — " + error.getMessage());
        }
    }

    private static void assertThat(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static void eq(double actual, double expected) {
        if (Math.abs(actual - expected) > 0.51) {
            throw new AssertionError("期望 " + expected + "，实际 " + actual);
        }
    }

    private interface Check {
        void run() throws Exception;
    }
}
