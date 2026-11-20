import io.quack.client.AppContext;
import io.quack.client.ui.AppWindow;
import io.quack.client.ui.LoginView;
import javafx.application.Platform;
import javafx.event.Event;
import javafx.scene.Node;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.stage.Screen;
import javafx.stage.Stage;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** 登录页卡片右上角的三个按钮：最小化 / 最大化 / 关闭 是否真的生效 */
public class LoginChromeProbe {

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
            AppWindow window = new AppWindow(stage, "login-chrome-probe", 360, 540, false);
            window.setTitleBarVisible(false);
            AppContext ctx = new AppContext(window);
            window.setContent(new LoginView(ctx, null, null));
            window.show();
            window.setMinSize(320, 460);
            window.resizeTo(360, 540);

            after(600, () -> {
                List<Node> buttons = window.root().lookupAll(".login-chrome-button").stream().toList();
                check("三个按钮都在", buttons.size() == 3, "找到 " + buttons.size());

                // 依次：—（最小化）、□（最大化）、✕（关闭）
                click(buttons.get(0));
                after(700, () -> {
                    check("最小化", stage.isIconified(), "isIconified=" + stage.isIconified());
                    stage.setIconified(false);

                    after(500, () -> {
                        click(buttons.get(1));
                        after(800, () -> {
                            double width = stage.getWidth();
                            double screen = Screen.getPrimary().getVisualBounds().getWidth();
                            check("最大化", Math.abs(width - screen) < 40,
                                    String.format("%.0f vs %.0f", width, screen));

                            click(buttons.get(1));
                            after(700, () -> {
                                check("再点一次还原", Math.abs(stage.getWidth() - 360) < 40,
                                        "宽=" + Math.round(stage.getWidth()));

                                click(buttons.get(2));
                                after(500, () -> {
                                    check("关闭", !stage.isShowing(), "isShowing=" + stage.isShowing());
                                    System.out.println(failures == 0
                                            ? "LOGINCHROME OK" : "LOGINCHROME FAILED: " + failures);
                                    Platform.exit();
                                    System.exit(failures == 0 ? 0 : 1);
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

    private static void click(Node node) {
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
