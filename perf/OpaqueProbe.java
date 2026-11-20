import io.quack.client.AppContext;
import io.quack.client.ui.AppWindow;
import io.quack.client.ui.MainView;
import io.quack.common.dto.LoginResp;
import io.quack.common.model.ChatGroup;
import io.quack.common.model.ChatMessage;
import io.quack.common.model.ChatType;
import io.quack.common.model.Friend;
import io.quack.common.model.MsgType;
import io.quack.common.model.User;
import javafx.animation.AnimationTimer;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.input.MouseButton;
import javafx.scene.robot.Robot;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

import java.util.ArrayList;
import java.util.List;

/** 对比：透明窗口 vs 不透明窗口（UNDECORATED）缩放时的帧率 */
public class OpaqueProbe {

    public static void main(String[] args) {
        Platform.startup(() -> {
            Stage stage = new Stage(StageStyle.UNDECORATED);
            AppWindow window = new AppWindow(stage, "opaque", 1000, 700, false);
            AppContext ctx = new AppContext(window);
            seed(ctx);
            window.setContent(new MainView(ctx), false);
            stage.setOpacity(1);
            window.show();

            after(600, () -> {
                // 实验：拖动前把内容固定住，看帧率上限
                javafx.scene.Node content = window.root().lookup(".content-host");
                if (content instanceof javafx.scene.layout.Region region) {
                    region.setMinSize(region.getWidth(), region.getHeight());
                    region.setPrefSize(region.getWidth(), region.getHeight());
                    region.setMaxSize(region.getWidth(), region.getHeight());
                }
                resize(stage, result -> {
                    System.out.println("FROZEN-RESIZE " + result);
                    Platform.exit();
                    System.exit(0);
                });
            });
        });
    }

    private static void resize(Stage stage, java.util.function.Consumer<String> done) {
        Robot robot = new Robot();
        double edgeX = stage.getX() + stage.getWidth() - 2;
        double edgeY = stage.getY() + stage.getHeight() / 2;
        List<Double> deltas = new ArrayList<>();
        long[] last = {0};
        boolean[] dragging = {true};

        robot.mouseMove((int) edgeX, (int) edgeY);
        robot.mousePress(MouseButton.PRIMARY);
        new AnimationTimer() {
            int step;

            @Override
            public void handle(long nanos) {
                if (last[0] != 0) {
                    deltas.add((nanos - last[0]) / 1_000_000.0);
                }
                last[0] = nanos;
                if (step < 40) {
                    robot.mouseMove((int) (edgeX + step * 4), (int) edgeY);
                    step++;
                } else if (dragging[0]) {
                    dragging[0] = false;
                    robot.mouseRelease(MouseButton.PRIMARY);
                } else {
                    stop();
                    done.accept(summary(deltas));
                }
            }
        }.start();
    }

    private static String summary(List<Double> deltas) {
        double sum = 0;
        double max = 0;
        for (double value : deltas) {
            sum += value;
            max = Math.max(max, value);
        }
        double avg = sum / deltas.size();
        return String.format("frames=%d avg=%.2fms => %.0f FPS max=%.2fms",
                deltas.size(), avg, 1000.0 / avg, max);
    }

    private static void after(long millis, Runnable action) {
        new javafx.animation.Timeline(new javafx.animation.KeyFrame(
                javafx.util.Duration.millis(millis), e -> action.run())).play();
    }

    private static void seed(AppContext ctx) {
        List<Friend> friends = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            friends.add(new Friend(String.valueOf(10010 + i), "好友" + i, "好友" + i, i % 2 == 0));
        }
        ctx.store().reset(new LoginResp(new User(10001L, "alice", "Alice", null),
                friends, new ArrayList<ChatGroup>()));
        var conversation = ctx.store().find(ChatType.PRIVATE, "10010");
        long now = System.currentTimeMillis();
        for (int i = 0; i < 200; i++) {
            conversation.messages().add(new ChatMessage("o" + i, "oc" + i,
                    i % 2 == 0 ? "10001" : "10010", i % 2 == 0 ? "Alice" : "好友0",
                    "10010", ChatType.PRIVATE, MsgType.TEXT,
                    "不透明窗口测试 " + i, now - (200 - i) * 30_000L));
        }
    }
}
