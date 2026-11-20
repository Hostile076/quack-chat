import io.quack.client.ui.ModalShell;
import javafx.application.Platform;
import javafx.event.Event;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.stage.Screen;
import javafx.stage.Stage;

import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** 每个弹窗（ModalShell）都应具备最小化 / 最大化 / 关闭 */
public class ModalChromeProbe {

    private static int failures;

    public static void main(String[] args) throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        Platform.startup(() -> {
            Platform.setImplicitExit(false);
            started.countDown();
        });
        started.await(10, TimeUnit.SECONDS);

        Platform.runLater(() -> {
            ModalShell shell = new ModalShell(null, "modal-chrome-probe", 420, 300, false);
            shell.setContent(new Label("probe"));
            shell.showAndWait();

            after(600, () -> {
                List<Node> buttons = shell.root().lookupAll(".title-button").stream()
                        .sorted(Comparator.comparingDouble((Node n) -> n.localToScene(
                                n.getBoundsInLocal()).getMinX()))
                        .toList();
                check("弹窗有三个窗口按钮", buttons.size() == 3, "找到 " + buttons.size());
                if (buttons.size() < 3) {
                    finish();
                    return;
                }
                Stage stage = shell.stage();

                click(buttons.get(0));
                after(700, () -> {
                    check("弹窗最小化", stage.isIconified(), "isIconified=" + stage.isIconified());
                    stage.setIconified(false);

                    after(500, () -> {
                        click(buttons.get(1));
                        after(800, () -> {
                            double width = stage.getWidth();
                            double screen = Screen.getPrimary().getVisualBounds().getWidth();
                            check("弹窗最大化", Math.abs(width - screen) < 40,
                                    String.format("%.0f vs %.0f", width, screen));

                            click(buttons.get(1));
                            after(700, () -> {
                                check("弹窗还原", Math.abs(stage.getWidth() - 420) < 40,
                                        "宽=" + Math.round(stage.getWidth()));

                                click(buttons.get(2));
                                after(500, () -> {
                                    check("弹窗关闭", !stage.isShowing(),
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
        System.out.println(failures == 0 ? "MODALCHROME OK" : "MODALCHROME FAILED: " + failures);
        Platform.exit();
        System.exit(failures == 0 ? 0 : 1);
    }

    private static void click(Node node) {
        // TitleBar 里是 Button（setOnAction），直接 fire() 才会走 action
        if (node instanceof javafx.scene.control.Button button) {
            button.fire();
            return;
        }
        Event.fireEvent(node, new MouseEvent(MouseEvent.MOUSE_CLICKED, 0, 0, 0, 0,
                MouseButton.PRIMARY, 1, false, false, false, false, true, false, false,
                true, false, false, null));
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
