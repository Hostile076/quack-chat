import io.quack.client.AppContext;
import io.quack.client.ui.AppWindow;
import io.quack.client.ui.LoginView;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.input.MouseButton;
import javafx.scene.robot.Robot;
import javafx.stage.Stage;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * 登录页拖动回归：没有标题栏之后，窗口必须还能被拖动。
 *
 * <p>用真实的 {@link Robot} 鼠标事件验证三件事：
 * <ol>
 *   <li>从窗口顶部空白带往下拖 → 窗口跟着移动；</li>
 *   <li>从表单里的标题文字拖动 → 窗口跟着移动；</li>
 *   <li>从输入框里拖动 → 窗口<b>不动</b>（那是选文字，不是拖窗）。</li>
 * </ol>
 */
public class LoginDragProbe {

    private static int passed;
    private static final List<String> failures = new ArrayList<>();
    private static Stage stage;
    private static AppWindow window;
    private static Robot robot;

    public static void main(String[] args) throws Exception {
        CompletableFuture<Void> started = new CompletableFuture<>();
        Platform.startup(() -> started.complete(null));
        started.get(20, TimeUnit.SECONDS);

        stage = fx(Stage::new);
        window = fx(() -> new AppWindow(stage, "登录拖动探针", 380, 600, false));
        fx(() -> {
            window.setTitleBarVisible(false);
            window.setContent(new LoginView(new AppContext(window)), false);
            window.show();
            stage.centerOnScreen();
            stage.toFront();
            robot = new Robot();
            return null;
        });
        Thread.sleep(800);
        layout();

        try {
            double startX = fx(stage::getX);
            double startY = fx(stage::getY);

            // 1. 顶部空白带
            dragScene(80, 16, 60, 40);
            Thread.sleep(300);
            check("从顶部空白带拖动 → 窗口移动", () ->
                    assertMoved(startX, startY));

            // 复位
            reset(startX, startY);

            // 2. 表单标题文字
            Node title = find(".login-title");
            check("表单标题存在", () -> assertThat(title != null, "找不到 .login-title"));
            dragNode(title, 50, 30);
            Thread.sleep(300);
            check("从标题文字拖动 → 窗口移动", () ->
                    assertMoved(startX, startY));

            // 复位
            reset(startX, startY);

            // 3. 输入框里拖动：窗口不能动
            Node field = anyField();
            check("登录输入框存在", () -> assertThat(field != null, "找不到输入框"));
            double beforeX = fx(stage::getX);
            double beforeY = fx(stage::getY);
            dragNode(field, 60, 40);
            Thread.sleep(300);
            check("从输入框拖动 → 窗口不动（选文字）", () -> fx(() -> {
                double dx = Math.abs(stage.getX() - beforeX);
                double dy = Math.abs(stage.getY() - beforeY);
                assertThat(dx <= 2 && dy <= 2, "窗口被拖动了: " + dx + "," + dy);
                return null;
            }));
        } catch (Throwable error) {
            failures.add("未捕获: " + error);
            error.printStackTrace();
        } finally {
            System.out.println("\n== 结果 ==");
            System.out.println("PASS " + passed + " / FAIL " + failures.size());
            for (String failure : failures) {
                System.out.println(" - " + failure);
            }
            Platform.exit();
            System.exit(failures.isEmpty() ? 0 : 1);
        }
    }

    // ------------------------------------------------------------------

    private static void dragScene(double sceneX, double sceneY, double dx, double dy) throws Exception {
        javafx.geometry.Point2D point = fx(() -> window.root().localToScreen(sceneX, sceneY));
        dragScreen(point.getX(), point.getY(), dx, dy);
    }

    private static void dragNode(Node node, double dx, double dy) throws Exception {
        javafx.geometry.Bounds bounds = fx(() -> node.localToScreen(node.getBoundsInLocal()));
        dragScreen(bounds.getMinX() + bounds.getWidth() / 2,
                bounds.getMinY() + bounds.getHeight() / 2, dx, dy);
    }

    private static void dragScreen(double x, double y, double dx, double dy) throws Exception {
        int steps = 12;
        for (int i = 0; i <= steps; i++) {
            final int index = i;
            fx(() -> {
                double px = x + dx * index / steps;
                double py = y + dy * index / steps;
                robot.mouseMove(px, py);
                if (index == 0) {
                    robot.mousePress(MouseButton.PRIMARY);
                } else if (index == steps) {
                    robot.mouseRelease(MouseButton.PRIMARY);
                }
                return null;
            });
            Thread.sleep(18);
        }
    }

    private static void reset(double x, double y) throws Exception {
        fx(() -> {
            stage.setX(x);
            stage.setY(y);
            return null;
        });
        Thread.sleep(200);
    }

    private static Boolean assertMoved(double startX, double startY) throws Exception {
        return fx(() -> {
            double dx = stage.getX() - startX;
            double dy = stage.getY() - startY;
            assertThat(dx > 30 && dy > 20, "窗口没有移动: dx=" + dx + " dy=" + dy);
            return null;
        });
    }

    private static Node find(String selector) throws Exception {
        return fx(() -> window.root().lookup(selector));
    }

    private static Node anyField() throws Exception {
        return fx(() -> {
            Parent root = window.root();
            Set<Node> fields = root.lookupAll(".login-field");
            return fields.isEmpty() ? null : fields.iterator().next();
        });
    }

    private static void layout() throws Exception {
        fx(() -> {
            window.root().applyCss();
            window.root().layout();
            return null;
        });
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

    private static <T> T fx(FxCallable<T> callable) throws Exception {
        CompletableFuture<T> result = new CompletableFuture<>();
        Platform.runLater(() -> {
            try {
                result.complete(callable.call());
            } catch (Throwable error) {
                result.completeExceptionally(error);
            }
        });
        return result.get(15, TimeUnit.SECONDS);
    }

    private interface Check {
        void run() throws Exception;
    }

    private interface FxCallable<T> {
        T call() throws Exception;
    }
}
