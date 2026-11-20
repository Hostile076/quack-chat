import io.quack.client.ui.AppWindow;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.stage.Screen;
import javafx.stage.Stage;

import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** 主窗口标题栏的三个按钮：最小化 / 最大化 / 关闭 */
public class MainChromeProbe {

    private static int failures;

    public static void main(String[] args) throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        Platform.startup(() -> {
            Platform.setImplicitExit(false);
            started.countDown();
        });
        started.await(10, TimeUnit.SECONDS);

        Platform.runLater(() -> {
            Stage stage = new Stage();
            AppWindow window = new AppWindow(stage, "main-chrome-probe", 1000, 680, true);
            window.show();

            after(700, () -> {
                double widthBefore = stage.getWidth();
                List<Node> buttons = window.root().lookupAll(".title-button").stream()
                        .sorted(Comparator.comparingDouble((Node n) -> n.localToScene(
                                n.getBoundsInLocal()).getMinX()))
                        .toList();
                check("主窗口有三个窗口按钮", buttons.size() == 3, "找到 " + buttons.size());
                if (buttons.size() < 3) {
                    finish();
                    return;
                }

                fire(buttons.get(0));
                after(700, () -> {
                    check("主窗口最小化", stage.isIconified(), "isIconified=" + stage.isIconified());
                    stage.setIconified(false);

                    after(500, () -> {
                        fire(buttons.get(1));
                        after(800, () -> {
                            double width = stage.getWidth();
                            double screen = Screen.getPrimary().getVisualBounds().getWidth();
                            check("主窗口最大化", Math.abs(width - screen) < 40,
                                    String.format("%.0f vs %.0f", width, screen));

                            fire(buttons.get(1));
                            after(700, () -> {
                                check("主窗口还原", Math.abs(stage.getWidth() - widthBefore) < 40,
                                        Math.round(widthBefore) + " -> " + Math.round(stage.getWidth()));

                                fire(buttons.get(2));
                                after(500, () -> {
                                    check("主窗口关闭", !stage.isShowing(),
                                            "isShowing=" + stage.isShowing());
                                    finish();
                                });
                            });
                        });
                    });
                });
            });
        });
        Thread.sleep(15000);
        System.exit(1);
    }

    private static void finish() {
        System.out.println(failures == 0 ? "MAINCHROME OK" : "MAINCHROME FAILED: " + failures);
        Platform.exit();
        System.exit(failures == 0 ? 0 : 1);
    }

    private static void fire(Node node) {
        if (node instanceof Button button) {
            button.fire();
        }
    }

    private static void check(String label, boolean ok, String detail) {
        if (!ok) {
            failures++;
        }
        System.out.printf("%s %s（%s）%n", ok ? "PASS" : "FAIL", label, detail);
    }

    private static void after(long millis, Runnable action) {
        new javafx.animation.Timeline(new javafx.animation.KeyFrame(
                javafx.util.Duration.millis(millis), e -> action.run())).play();
    }
}
