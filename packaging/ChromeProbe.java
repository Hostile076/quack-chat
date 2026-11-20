import io.quack.client.ui.AppWindow;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.geometry.Rectangle2D;
import javafx.scene.control.Label;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.StackPane;
import javafx.scene.robot.Robot;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.util.Duration;

import java.util.ArrayList;
import java.util.List;

/**
 * 真机验证无边框窗口的交互：拖右边缩放、拖标题栏移窗、最大化/还原。
 *
 * <p>注意：所有步骤必须串行执行（前一步结束后再排下一步的 PauseTransition），
 * 否则各个定时器会同时触发，测出来的东西没有意义。
 */
public class ChromeProbe {

    private static final StringBuilder REPORT = new StringBuilder();
    private static final List<Step> STEPS = new ArrayList<>();
    private static int failures;

    private record Step(long delayMillis, Runnable action) {
    }

    public static void main(String[] args) {
        Platform.startup(() -> {
            Stage stage = new Stage();
            AppWindow window = new AppWindow(stage, "chrome probe", 900, 640, true);
            StackPane content = new StackPane(new Label("probe"));
            content.setStyle("-fx-background-color: white;");
            window.setContent(content, false);
            stage.setAlwaysOnTop(true);
            window.show();
            stage.toFront();

            Robot robot = new Robot();
            System.out.println("screen visual = " + Screen.getPrimary().getVisualBounds()
                    + ", scale = " + Screen.getPrimary().getOutputScaleX());

            STEPS.add(new Step(1200, () -> {
                reset(stage);
                System.out.println("start bounds = " + bounds(stage));
            }));

            // 1) 拖右边缩放：分多步移动，产生连续的 DRAGGED 事件
            STEPS.add(new Step(400, () -> {
                double edgeX = stage.getX() + stage.getWidth() - 12;
                double centerY = stage.getY() + stage.getHeight() / 2;
                robot.mouseMove(edgeX, centerY);
                robot.mousePress(MouseButton.PRIMARY);
                for (int i = 1; i <= 10; i++) {
                    robot.mouseMove(edgeX + i * 9, centerY);
                }
                robot.mouseRelease(MouseButton.PRIMARY);
            }));
            STEPS.add(new Step(600, () -> {
                System.out.println("after resize = " + bounds(stage));
                double grew = stage.getWidth() - 900;
                check("拖右边放大 90px（实际 " + Math.round(grew) + "px）", grew > 60 && grew < 130);
            }));

            // 2) 拖标题栏移动窗口
            STEPS.add(new Step(400, () -> {
                double fromX = stage.getX() + 240;
                double fromY = stage.getY() + 22;
                robot.mouseMove(fromX, fromY);
                robot.mousePress(MouseButton.PRIMARY);
                for (int i = 1; i <= 10; i++) {
                    robot.mouseMove(fromX + i * 6, fromY + i * 4);
                }
                robot.mouseRelease(MouseButton.PRIMARY);
            }));
            STEPS.add(new Step(600, () -> {
                System.out.println("after drag = " + bounds(stage));
                check("拖标题栏移动了 (60,40)",
                        Math.abs(stage.getX() - 260) < 25 && Math.abs(stage.getY() - 190) < 25);
            }));

            // 3) 最大化 / 还原
            STEPS.add(new Step(300, () -> {
                reset(stage);
                window.toggleMaximize();
            }));
            STEPS.add(new Step(700, () -> {
                Rectangle2D bounds = Screen.getPrimary().getVisualBounds();
                System.out.println("maximized = " + bounds(stage));
                check("最大化铺满可视区域",
                        Math.abs(stage.getWidth() - bounds.getWidth()) < 4
                                && Math.abs(stage.getHeight() - bounds.getHeight()) < 4);
                window.toggleMaximize();
            }));
            STEPS.add(new Step(700, () -> {
                System.out.println("restored = " + bounds(stage));
                check("还原回 900x640",
                        Math.abs(stage.getWidth() - 900) < 4 && Math.abs(stage.getHeight() - 640) < 4);
            }));

            runStep(0);
        });
    }

    private static void runStep(int index) {
        if (index >= STEPS.size()) {
            System.out.println(REPORT);
            System.out.println(failures == 0 ? "ALL PASS" : (failures + " FAILED"));
            Platform.exit();
            System.exit(failures == 0 ? 0 : 1);
            return;
        }
        Step step = STEPS.get(index);
        PauseTransition pause = new PauseTransition(Duration.millis(step.delayMillis()));
        pause.setOnFinished(e -> {
            try {
                step.action().run();
            } catch (RuntimeException ex) {
                ex.printStackTrace();
                failures++;
            }
            runStep(index + 1);
        });
        pause.play();
    }

    private static void reset(Stage stage) {
        stage.setX(200);
        stage.setY(150);
        stage.setWidth(900);
        stage.setHeight(640);
    }

    private static String bounds(Stage stage) {
        return "x=" + Math.round(stage.getX()) + " y=" + Math.round(stage.getY())
                + " w=" + Math.round(stage.getWidth()) + " h=" + Math.round(stage.getHeight());
    }

    private static void check(String label, boolean ok) {
        REPORT.append(ok ? "[PASS] " : "[FAIL] ").append(label).append('\n');
        if (!ok) {
            failures++;
        }
    }
}
