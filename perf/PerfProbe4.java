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
import javafx.scene.Node;
import javafx.scene.input.MouseButton;
import javafx.scene.robot.Robot;
import javafx.stage.Stage;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 真滚轮测试：Robot 连续滚动消息列表，统计帧间隔 */
public class PerfProbe4 {

    public static void main(String[] args) {
        Platform.startup(() -> {
            Stage stage = new Stage();
            AppWindow window = new AppWindow(stage, "perf", 1000, 720, true);
            AppContext ctx = new AppContext(window);
            seed(ctx);
            window.setContent(new MainView(ctx), false);
            window.show();

            Platform.runLater(() -> {
                Node list = window.root().lookup(".message-list");
                if (list == null) {
                    System.out.println("找不到消息列表");
                    Platform.exit();
                    return;
                }
                Robot robot = new Robot();
                var bounds = list.localToScreen(list.getBoundsInLocal());
                robot.mouseMove(bounds.getMinX() + bounds.getWidth() / 2, bounds.getMinY() + 40);
                robot.mousePress(MouseButton.PRIMARY);
                robot.mouseRelease(MouseButton.PRIMARY);

                List<Double> deltas = new ArrayList<>();
                long[] last = {0};
                int[] frame = {0};

                new AnimationTimer() {
                    @Override
                    public void handle(long nanos) {
                        if (last[0] != 0 && frame[0] >= 30) {
                            deltas.add((nanos - last[0]) / 1_000_000.0);
                        }
                        last[0] = nanos;
                        if (frame[0] < 330) {
                            if (frame[0] % 3 == 0) {
                                robot.mouseWheel(2);
                            }
                            frame[0]++;
                        } else {
                            stop();
                            System.out.println("WHEEL " + summary(deltas));
                            Platform.exit();
                            System.exit(0);
                        }
                    }
                }.start();
            });
        });
    }

    private static void seed(AppContext ctx) {
        List<Friend> friends = new ArrayList<>();
        friends.add(new Friend("10010", "好友0", "好友0", true));
        ctx.store().reset(new LoginResp(new User(10001L, "alice", "Alice", null),
                friends, new ArrayList<ChatGroup>()));

        var conversation = ctx.store().find(ChatType.PRIVATE, "10010");
        if (conversation == null) {
            return;
        }
        long now = System.currentTimeMillis();
        for (int i = 0; i < 400; i++) {
            boolean mine = i % 2 == 0;
            conversation.messages().add(new ChatMessage(
                    "w" + i, "wc" + i,
                    mine ? "10001" : "10010",
                    mine ? "Alice" : "好友0",
                    "10010", ChatType.PRIVATE, MsgType.TEXT,
                    "第 " + i + " 条：滚轮滚动测试，文本长度适中以便观察气泡重排。",
                    now - (400 - i) * 30_000L));
        }
    }

    private static String summary(List<Double> deltas) {
        List<Double> sorted = new ArrayList<>(deltas);
        Collections.sort(sorted);
        double sum = 0;
        int janky = 0;
        for (double value : deltas) {
            sum += value;
            if (value > 33) {
                janky++;
            }
        }
        return String.format("frames=%d avg=%.2fms p95=%.2fms max=%.2fms janky=%d",
                deltas.size(), sum / deltas.size(),
                sorted.get((int) (sorted.size() * 0.95)),
                sorted.get(sorted.size() - 1), janky);
    }
}
