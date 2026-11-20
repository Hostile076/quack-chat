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
import javafx.geometry.Rectangle2D;
import javafx.scene.input.MouseButton;
import javafx.scene.robot.Robot;
import javafx.stage.Screen;
import javafx.stage.Stage;

import java.util.ArrayList;
import java.util.List;

/**
 * 拖拽帧率探针：用 Robot 真实拖窗口（移动 + 缩放），统计 FPS。
 */
public class DragProbe {

    public static void main(String[] args) {
        Platform.startup(() -> {
            Stage stage = new Stage();
            AppWindow window = new AppWindow(stage, "drag-probe", 1000, 700, true);
            AppContext ctx = new AppContext(window);
            seed(ctx);
            window.setContent(new MainView(ctx), false);
            window.show();

            after(600, () -> {
                moveDrag(window, stage, moveResult -> {
                    System.out.println("MOVE  " + moveResult);
                    after(400, () -> resizeDrag(window, stage, resizeResult -> {
                        System.out.println("RESIZE " + resizeResult);
                        Platform.exit();
                        System.exit(0);
                    }));
                });
            });
        });
    }

    /** 拖标题栏移动 200px */
    private static void moveDrag(AppWindow window, Stage stage, java.util.function.Consumer<String> done) {
        Robot robot = new Robot();
        Rectangle2D screen = Screen.getPrimary().getVisualBounds();
        double startX = stage.getX() + stage.getWidth() / 2;
        double startY = stage.getY() + 22;

        List<Double> deltas = new ArrayList<>();
        long[] last = {0};
        long[] dragStart = {0};
        boolean[] dragging = {true};

        robot.mouseMove((int) startX, (int) startY);
        robot.mousePress(MouseButton.PRIMARY);

        AnimationTimer timer = new AnimationTimer() {
            int step;

            @Override
            public void handle(long nanos) {
                if (last[0] != 0 && dragStart[0] != 0) {
                    deltas.add((nanos - last[0]) / 1_000_000.0);
                }
                last[0] = nanos;
                if (dragStart[0] == 0) {
                    dragStart[0] = nanos;
                }
                if (step < 40) {
                    robot.mouseMove((int) (startX + step * 5), (int) startY);
                    step++;
                } else if (dragging[0]) {
                    dragging[0] = false;
                    robot.mouseRelease(MouseButton.PRIMARY);
                } else {
                    stop();
                    done.accept(summary(deltas));
                }
            }
        };
        timer.start();
    }

    /** 拖右边缘缩放 200px */
    private static void resizeDrag(AppWindow window, Stage stage, java.util.function.Consumer<String> done) {
        Robot robot = new Robot();
        double edgeX = stage.getX() + stage.getWidth() - 2;
        double edgeY = stage.getY() + stage.getHeight() / 2;

        List<Double> deltas = new ArrayList<>();
        long[] last = {0};
        boolean[] dragging = {true};

        robot.mouseMove((int) edgeX, (int) edgeY);
        robot.mousePress(MouseButton.PRIMARY);

        AnimationTimer timer = new AnimationTimer() {
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
        };
        timer.start();
    }

    private static String summary(List<Double> deltas) {
        if (deltas.size() < 5) {
            return "samples=" + deltas.size();
        }
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
        javafx.animation.Timeline timeline = new javafx.animation.Timeline(
                new javafx.animation.KeyFrame(javafx.util.Duration.millis(millis), e -> action.run()));
        timeline.play();
    }

    private static void seed(AppContext ctx) {
        List<Friend> friends = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            friends.add(new Friend(String.valueOf(10010 + i), "好友" + i, "好友" + i, i % 2 == 0));
        }
        ctx.store().reset(new LoginResp(new User(10001L, "alice", "Alice", null),
                friends, new ArrayList<ChatGroup>()));
        var conversation = ctx.store().find(ChatType.PRIVATE, "10010");
        if (conversation == null) {
            return;
        }
        long now = System.currentTimeMillis();
        for (int i = 0; i < 200; i++) {
            conversation.messages().add(new ChatMessage("d" + i, "dc" + i,
                    i % 2 == 0 ? "10001" : "10010",
                    i % 2 == 0 ? "Alice" : "好友0",
                    "10010", ChatType.PRIVATE, MsgType.TEXT,
                    "拖拽测试消息 " + i + "，内容长度中等。", now - (200 - i) * 30_000L));
        }
    }
}
