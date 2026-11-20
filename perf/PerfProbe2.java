import io.quack.client.AppContext;
import io.quack.client.ui.AppWindow;
import io.quack.client.ui.MainView;
import io.quack.client.ui.MessageCell;
import io.quack.common.dto.LoginResp;
import io.quack.common.model.ChatGroup;
import io.quack.common.model.ChatMessage;
import io.quack.common.model.ChatType;
import io.quack.common.model.Friend;
import io.quack.common.model.MsgType;
import io.quack.common.model.User;
import javafx.animation.AnimationTimer;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.scene.Scene;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.ListView;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 性能基线：两段测量
 * A. 连续滚动（2000 条消息，每帧前进一条）
 * B. 窗口拖拽缩放（真实 MainView，每帧改变窗口宽度）
 */
public class PerfProbe2 {

    private static final int MESSAGES = 2000;

    public static void main(String[] args) {
        Platform.startup(() -> {
            scrollPhase(result -> {
                System.out.println("SCROLL " + result);
                resizePhase(false, result2 -> {
                    System.out.println("RESIZE(shadow) " + result2);
                    resizePhase(true, result3 -> {
                        System.out.println("RESIZE(no-shadow) " + result3);
                        Platform.exit();
                        System.exit(0);
                    });
                });
            });
        });
    }

    // ------------------------------------------------------------------
    // A. 滚动
    // ------------------------------------------------------------------

    private static void scrollPhase(java.util.function.Consumer<String> done) {
        ListView<ChatMessage> list = new ListView<>();
        ObservableList<ChatMessage> items = FXCollections.observableArrayList();
        long now = System.currentTimeMillis();
        for (int i = 0; i < MESSAGES; i++) {
            boolean mine = i % 3 == 0;
            items.add(new ChatMessage("m" + i, "c" + i, mine ? "10001" : "10002",
                    mine ? "Alice" : "小鲍", "10001", ChatType.PRIVATE, MsgType.TEXT,
                    "第 " + i + " 条消息，用来测试滚动是否顺畅，内容稍微长一点更接近真实情况。",
                    now - (MESSAGES - i) * 1000L));
        }
        list.setItems(items);
        list.setCellFactory(view -> new MessageCell(
                () -> "10001", list.widthProperty(), message -> new ContextMenu()));

        Stage stage = new Stage();
        stage.setScene(new Scene(new StackPane(list), 1000, 720));
        stage.show();

        List<Double> deltas = new ArrayList<>();
        long[] last = {0};
        int[] frame = {0};
        new AnimationTimer() {
            @Override
            public void handle(long nanos) {
                if (last[0] != 0) {
                    deltas.add((nanos - last[0]) / 1_000_000.0);
                }
                last[0] = nanos;
                if (frame[0] < 420) {
                    list.scrollTo(frame[0] % (MESSAGES - 20));
                    frame[0]++;
                } else {
                    stop();
                    stage.close();
                    done.accept(summary(deltas));
                }
            }
        }.start();
    }

    // ------------------------------------------------------------------
    // B. 缩放
    // ------------------------------------------------------------------

    private static void resizePhase(boolean noShadow, java.util.function.Consumer<String> done) {
        Stage stage = new Stage();
        AppWindow window = new AppWindow(stage, "perf", 1000, 680, true);
        AppContext ctx = new AppContext(window);
        seed(ctx);
        window.setContent(new MainView(ctx), false);
        window.show();
        if (noShadow) {
            javafx.scene.Node frame = window.root().lookup(".window-frame");
            if (frame != null) {
                frame.setStyle("-fx-effect: null;");
            }
        }

        List<Double> deltas = new ArrayList<>();
        long[] last = {0};
        int[] frame = {0};
        new AnimationTimer() {
            @Override
            public void handle(long nanos) {
                if (last[0] != 0) {
                    if (frame[0] >= 60) {
                        deltas.add((nanos - last[0]) / 1_000_000.0);
                    }
                }
                last[0] = nanos;
                if (frame[0] < 300) {
                    double width = 960 + (frame[0] % 60) * 4;
                    double height = 640 + (frame[0] % 40) * 3;
                    stage.setWidth(width);
                    stage.setHeight(height);
                    frame[0]++;
                } else {
                    stop();
                    stage.close();
                    done.accept(summary(deltas));
                }
            }
        }.start();
    }

    private static void seed(AppContext ctx) {
        List<Friend> friends = new ArrayList<>();
        for (int i = 0; i < 24; i++) {
            friends.add(new Friend(String.valueOf(10010 + i), "好友" + i, "好友" + i, i % 2 == 0));
        }
        List<ChatGroup> groups = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            groups.add(new ChatGroup("q" + (10001 + i), "群聊" + i, 10));
        }
        ctx.store().reset(new LoginResp(new User(10001L, "alice", "Alice", null), friends, groups));

        long now = System.currentTimeMillis();
        var conversation = ctx.store().find(ChatType.PRIVATE, "10010");
        if (conversation == null) {
            return;
        }
        for (int i = 0; i < 300; i++) {
            boolean mine = i % 2 == 0;
            conversation.messages().add(new ChatMessage(
                    "s" + i, "sc" + i,
                    mine ? "10001" : "10010",
                    mine ? "Alice" : "好友0",
                    "10010", ChatType.PRIVATE, MsgType.TEXT,
                    "历史消息 " + i + "：这里是一段稍微长一点的文本，用来模拟真实聊天气泡的高度。",
                    now - (300 - i) * 60_000L));
        }
    }

    private static String summary(List<Double> deltas) {
        if (deltas.size() < 10) {
            return "samples=" + deltas.size();
        }
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
