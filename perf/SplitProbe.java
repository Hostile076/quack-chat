import io.quack.client.AppContext;
import io.quack.client.ui.AppWindow;
import io.quack.client.ui.MainView;
import io.quack.common.dto.LoginResp;
import io.quack.common.model.ChatGroup;
import io.quack.common.model.Friend;
import io.quack.common.model.User;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.Region;
import javafx.scene.robot.Robot;
import javafx.stage.Stage;

import java.util.ArrayList;
import java.util.List;

/** 模块分割线测试：拖动改变尺寸、最小尺寸夹取、双击复位 */
public class SplitProbe {

    private static int failures;
    private static final List<String> lines = new ArrayList<>();

    public static void main(String[] args) {
        Platform.startup(() -> {
            Stage stage = new Stage();
            AppWindow window = new AppWindow(stage, "split-probe", 1100, 720, true);
            AppContext ctx = new AppContext(window);
            seed(ctx);
            window.setContent(new MainView(ctx), false);
            window.show();
            // 窗口会恢复上次保存的尺寸，这里强制成测试尺寸
            stage.setWidth(1100);
            stage.setHeight(720);
            stage.setX(150);
            stage.setY(100);

            after(900, () -> {
                Region listHost = (Region) window.root().lookup(".list-host");
                Region composer = (Region) window.root().lookup(".composer");
                Node handleH = window.root().lookupAll(".split-handle").stream().findFirst().orElse(null);
                Node handleV = window.root().lookupAll(".split-handle-v").stream().findFirst().orElse(null);

                if (listHost == null || composer == null || handleH == null || handleV == null) {
                    System.out.println("找不到控件: listHost=" + listHost + " composer=" + composer
                            + " h=" + handleH + " v=" + handleV);
                    Platform.exit();
                    System.exit(1);
                }

                double startSidebar = listHost.getWidth();
                System.out.printf("debug: window=%.0fx%.0f listHost=%.0f rail=%.0f handle=%.0fx%.0f screen=%s%n",
                        stage.getWidth(), stage.getHeight(), listHost.getWidth(),
                        ((Region) window.root().lookup(".rail")).getWidth(),
                        handleH.getBoundsInLocal().getWidth(), handleH.getBoundsInLocal().getHeight(),
                        handleH.localToScreen(handleH.getBoundsInLocal()));
                double startComposer = composer.getHeight();

                // 1. 水平分割线往左拖 200：应被夹到最小 180
                drag(handleH, -200, 0, () -> {
                    double sidebar = listHost.getWidth();
                    check("侧栏向左拖被夹取到最小宽度",
                            Math.abs(sidebar - 180) <= 6 && sidebar < startSidebar,
                            String.format("%.0f -> %.0f", startSidebar, sidebar));

                    // 2. 往右拖 900：应被夹到「窗口宽 - 420」
                    drag(handleH, 900, 0, () -> {
                        double sidebar2 = listHost.getWidth();
                        double expectedMax = Math.max(220, stage.getWidth() - 420);
                        check("侧栏向右拖被夹取到最大宽度",
                                Math.abs(sidebar2 - expectedMax) <= 8,
                                String.format("%.0f (上限 %.0f)", sidebar2, expectedMax));

                        // 3. 垂直分割线往上拖 200：输入区夹到最小 96
                        drag(handleV, 0, -200, () -> {
                            double composerHeight = composer.getHeight();
                            check("输入区向上拖被夹取到最小高度",
                                    Math.abs(composerHeight - 96) <= 8 && composerHeight < startComposer,
                                    String.format("%.0f -> %.0f", startComposer, composerHeight));

                            // 4. 双击复位
                            double before = composer.getHeight();
                            Robot robot = new Robot();
                            var bounds = handleV.localToScreen(handleV.getBoundsInLocal());
                            robot.mouseMove((int) (bounds.getMinX() + bounds.getWidth() / 2),
                                    (int) (bounds.getMinY() + bounds.getHeight() / 2));
                            robot.mouseClick(MouseButton.PRIMARY);
                            robot.mouseClick(MouseButton.PRIMARY);
                            after(250, () -> {
                                double after2 = composer.getHeight();
                                check("双击分割线复位到默认高度",
                                        Math.abs(after2 - 132) <= 10,
                                        String.format("%.0f -> %.0f", before, after2));
                                System.out.println(failures == 0 ? "SPLIT OK" : "SPLIT FAILED: " + failures);
                                lines.forEach(System.out::println);
                                Platform.exit();
                                System.exit(failures == 0 ? 0 : 1);
                            });
                        });
                    });
                });
            });
        });
    }

    private static void drag(Node handle, double dx, double dy, Runnable done) {
        Robot robot = new Robot();
        var bounds = handle.localToScreen(handle.getBoundsInLocal());
        double x = bounds.getMinX() + bounds.getWidth() / 2;
        double y = bounds.getMinY() + bounds.getHeight() / 2;
        robot.mouseMove((int) x, (int) y);
        robot.mousePress(MouseButton.PRIMARY);
        for (int step = 1; step <= 10; step++) {
            robot.mouseMove((int) (x + dx * step / 10.0), (int) (y + dy * step / 10.0));
        }
        robot.mouseRelease(MouseButton.PRIMARY);
        after(220, done);
    }

    private static void check(String label, boolean ok, String detail) {
        if (!ok) {
            failures++;
        }
        lines.add(String.format("%s  %s  (%s)", ok ? "PASS" : "FAIL", label, detail));
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
        var conversation = ctx.store().find(io.quack.common.model.ChatType.PRIVATE, "10010");
        if (conversation != null) {
            ctx.store().conversations().size();
        }
    }
}
