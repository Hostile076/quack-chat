package io.quack.client.ui;

import javafx.application.Platform;
import javafx.stage.Screen;
import javafx.stage.Stage;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** 窗口控制（最小化 / 最大化 / 关闭）功能测试 */
public class WindowControlsProbe {

    private static int failures;

    public static void main(String[] args) throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        Platform.startup(() -> {
            Platform.setImplicitExit(false);
            started.countDown();
        });
        started.await(10, TimeUnit.SECONDS);

        CountDownLatch done = new CountDownLatch(1);
        Platform.runLater(() -> {
            Stage stage = new Stage();
            AppWindow window = new AppWindow(stage, "controls-probe", 360, 540, false);
            window.setTitleBarVisible(false);
            window.show();
            window.setMinSize(320, 460);
            window.resizeTo(360, 540);

            after(500, () -> {
                // 1. 最小化
                stage.setIconified(true);
                after(600, () -> {
                    boolean iconified = stage.isIconified();
                    check("最小化", iconified, "isIconified=" + iconified);
                    stage.setIconified(false);

                    after(400, () -> {
                        // 2. 最大化
                        System.err.println("resizable=" + stage.isResizable()
                                + " maxW=" + stage.getMaxWidth()
                                + " size=" + stage.getWidth() + "x" + stage.getHeight());
                        window.toggleMaximize();
                        after(700, () -> {
                            double width = stage.getWidth();
                            double screen = Screen.getPrimary().getVisualBounds().getWidth();
                            boolean maximized = Math.abs(width - screen) < 40;
                            check("最大化", maximized,
                                    String.format("%.0f vs 屏幕 %.0f", width, screen));

                            // 3. 还原
                            System.err.println("resizable=" + stage.isResizable()
                                + " maxW=" + stage.getMaxWidth()
                                + " size=" + stage.getWidth() + "x" + stage.getHeight());
                        window.toggleMaximize();
                            after(700, () -> {
                                boolean restored = Math.abs(stage.getWidth() - 360) < 40;
                                check("还原", restored, "宽=" + Math.round(stage.getWidth()));

                                // 4. 关闭
                                stage.close();
                                after(400, () -> {
                                    check("关闭", !stage.isShowing(), "isShowing=" + stage.isShowing());
                                    System.out.println(failures == 0
                                            ? "WINDOWCONTROLS OK" : "WINDOWCONTROLS FAILED: " + failures);
                                    Platform.exit();
                                    System.exit(failures == 0 ? 0 : 1);
                                });
                            });
                        });
                    });
                });
            });
        });

        done.await(15, TimeUnit.SECONDS);
        Platform.exit();
        System.exit(failures == 0 ? 0 : 1);
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
