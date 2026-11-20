import io.quack.client.AppContext;
import io.quack.client.ui.AppWindow;
import io.quack.client.ui.MainView;
import io.quack.client.ui.ModalShell;
import io.quack.common.dto.LoginResp;
import io.quack.common.model.ChatGroup;
import io.quack.common.model.Friend;
import io.quack.common.model.User;
import javafx.application.Platform;
import javafx.geometry.Rectangle2D;
import javafx.scene.control.Label;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.VBox;
import javafx.scene.robot.Robot;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.util.ArrayList;
import java.util.List;

/**
 * 八向缩放功能测试：对每个方向真实拖动一次，断言窗口几何按预期变化。
 *
 * <p>这是"缩放到底能不能用"的硬证据，不看帧率，只看结果。
 */
public class ResizeProbe {

    private static final double BASE_X = 200;
    private static final double BASE_Y = 150;
    private static final double BASE_W = 1000;
    private static final double BASE_H = 700;
    private static final double DRAG = 60;

    private static int failures;
    private static final List<String> results = new ArrayList<>();

    public static void main(String[] args) {
        Platform.startup(() -> {
            Stage stage = new Stage();
            AppWindow window = new AppWindow(stage, "resize-probe", (int) BASE_W, (int) BASE_H, true);
            AppContext ctx = new AppContext(window);
            seed(ctx);
            window.setContent(new MainView(ctx), false);
            window.show();
            stage.setX(BASE_X);
            stage.setY(BASE_Y);

            after(900, () -> {
                testAll(stage, () -> {
                    System.out.println("--- 主窗口 ---");
                    results.forEach(System.out::println);
                    testModal(stage, () -> {
                        System.out.println("--- 弹窗 ---");
                        results.forEach(System.out::println);
                        System.out.println(failures == 0 ? "RESIZE OK" : "RESIZE FAILED: " + failures);
                        Platform.exit();
                        System.exit(failures == 0 ? 0 : 1);
                    });
                });
            });
        });
    }

    // ------------------------------------------------------------------
    // 八个方向
    // ------------------------------------------------------------------

    private static void testAll(Stage stage, Runnable done) {
        String[] names = {"TOP", "BOTTOM", "LEFT", "RIGHT", "TOP_LEFT", "TOP_RIGHT", "BOTTOM_LEFT", "BOTTOM_RIGHT"};
        testOne(stage, names, 0, done);
    }

    private static void testOne(Stage stage, String[] names, int index, Runnable done) {
        if (index >= names.length) {
            done.run();
            return;
        }
        String name = names[index];
        reset(stage);
        after(250, () -> {
            drag(stage, name, () -> after(250, () -> testOne(stage, names, index + 1, done)));
        });
    }

    private static void reset(Stage stage) {
        stage.setX(BASE_X);
        stage.setY(BASE_Y);
        stage.setWidth(BASE_W);
        stage.setHeight(BASE_H);
    }

    private static void drag(Stage stage, String name, Runnable done) {
        Robot robot = new Robot();
        double x = stage.getX();
        double y = stage.getY();
        double w = stage.getWidth();
        double h = stage.getHeight();

        double startX;
        double startY;
        double dx;
        double dy;
        switch (name) {
            case "TOP" -> { startX = x + w / 2; startY = y + 3; dx = 0; dy = DRAG; }
            case "BOTTOM" -> { startX = x + w / 2; startY = y + h - 3; dx = 0; dy = DRAG; }
            case "LEFT" -> { startX = x + 3; startY = y + h / 2; dx = DRAG; dy = 0; }
            case "RIGHT" -> { startX = x + w - 3; startY = y + h / 2; dx = DRAG; dy = 0; }
            case "TOP_LEFT" -> { startX = x + 3; startY = y + 3; dx = DRAG; dy = DRAG; }
            case "TOP_RIGHT" -> { startX = x + w - 3; startY = y + 3; dx = DRAG; dy = DRAG; }
            case "BOTTOM_LEFT" -> { startX = x + 3; startY = y + h - 3; dx = DRAG; dy = DRAG; }
            default -> { startX = x + w - 3; startY = y + h - 3; dx = DRAG; dy = DRAG; }
        }

        robot.mouseMove((int) startX, (int) startY);
        robot.mousePress(MouseButton.PRIMARY);
        for (int step = 1; step <= 10; step++) {
            robot.mouseMove((int) (startX + dx * step / 10.0), (int) (startY + dy * step / 10.0));
        }
        robot.mouseRelease(MouseButton.PRIMARY);
        after(120, () -> {
            check(name, stage);
            done.run();
        });
    }

    private static void check(String name, Stage stage) {
        double x = stage.getX();
        double y = stage.getY();
        double w = stage.getWidth();
        double h = stage.getHeight();

        double expectedWidth = BASE_W;
        double expectedHeight = BASE_H;
        double expectedX = BASE_X;
        double expectedY = BASE_Y;

        switch (name) {
            case "RIGHT" -> expectedWidth = BASE_W + DRAG;
            case "BOTTOM" -> expectedHeight = BASE_H + DRAG;
            case "LEFT" -> { expectedWidth = BASE_W - DRAG; expectedX = BASE_X + DRAG; }
            case "TOP" -> { expectedHeight = BASE_H - DRAG; expectedY = BASE_Y + DRAG; }
            case "TOP_LEFT" -> {
                expectedWidth = BASE_W - DRAG; expectedX = BASE_X + DRAG;
                expectedHeight = BASE_H - DRAG; expectedY = BASE_Y + DRAG;
            }
            case "TOP_RIGHT" -> {
                expectedWidth = BASE_W + DRAG;
                expectedHeight = BASE_H - DRAG; expectedY = BASE_Y + DRAG;
            }
            case "BOTTOM_LEFT" -> {
                expectedWidth = BASE_W - DRAG; expectedX = BASE_X + DRAG;
                expectedHeight = BASE_H + DRAG;
            }
            case "BOTTOM_RIGHT" -> {
                expectedWidth = BASE_W + DRAG;
                expectedHeight = BASE_H + DRAG;
            }
            default -> {
            }
        }

        boolean ok = near(w, expectedWidth) && near(h, expectedHeight)
                && near(x, expectedX) && near(y, expectedY);
        if (!ok) {
            failures++;
        }
        results.add(String.format("%-14s %s  %sx%s @ %s,%s (期望 %sx%s @ %s,%s)",
                name, ok ? "PASS" : "FAIL",
                Math.round(w), Math.round(h), Math.round(x), Math.round(y),
                Math.round(expectedWidth), Math.round(expectedHeight),
                Math.round(expectedX), Math.round(expectedY)));
    }

    // ------------------------------------------------------------------
    // 弹窗
    // ------------------------------------------------------------------

    private static void testModal(Stage owner, Runnable done) {
        ModalShell shell = new ModalShell(owner, "resize-modal", 420, 520);
        shell.setContent(new VBox(new Label("弹窗缩放测试")));
        shell.showAndWait();
        ModalResize.window = shell.stage();
        after(400, () -> {
            Stage stage = ModalResize.window;
            double startWidth = stage.getWidth();
            double startHeight = stage.getHeight();
            Robot robot = new Robot();
            double x = stage.getX() + stage.getWidth() - 3;
            double y = stage.getY() + stage.getHeight() / 2;

            robot.mouseMove((int) x, (int) y);
            robot.mousePress(MouseButton.PRIMARY);
            for (int step = 1; step <= 10; step++) {
                robot.mouseMove((int) (x + DRAG * step / 10.0), (int) y);
            }
            robot.mouseRelease(MouseButton.PRIMARY);

            after(200, () -> {
                boolean ok = stage.getWidth() > startWidth + 30;
                if (!ok) {
                    failures++;
                }
                results.add(String.format("MODAL-RIGHT    %s  %.0f -> %.0f",
                        ok ? "PASS" : "FAIL", startWidth, stage.getWidth()));
                stage.close();
                done.run();
            });
        });
    }

    private static final class ModalResize {
        static Stage window;
    }

    // ------------------------------------------------------------------

    private static boolean near(double actual, double expected) {
        return Math.abs(actual - expected) <= 10;
    }

    private static void after(long millis, Runnable action) {
        new javafx.animation.Timeline(new javafx.animation.KeyFrame(
                javafx.util.Duration.millis(millis), e -> action.run())).play();
    }

    private static void seed(AppContext ctx) {
        List<Friend> friends = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            friends.add(new Friend(String.valueOf(10010 + i), "好友" + i, "好友" + i, true));
        }
        ctx.store().reset(new LoginResp(new User(10001L, "alice", "Alice", null),
                friends, new ArrayList<ChatGroup>()));
        ctx.store().find(io.quack.common.model.ChatType.PRIVATE, "10010");
    }
}
