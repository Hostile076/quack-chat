import io.quack.client.ui.MessageCell;
import io.quack.common.model.ChatMessage;
import io.quack.common.model.ChatType;
import io.quack.common.model.MsgType;
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
 * 滚动性能探针：2000 条消息，每帧 scrollTo，统计帧间隔。
 *
 * <p>这是优化前后的同一把尺子：平均帧时间、p95、超过 33ms（掉帧）的帧数。
 */
public class PerfProbe {

    private static final int MESSAGE_COUNT = 2000;
    private static final int FRAMES = 480;

    public static void main(String[] args) {
        Platform.startup(() -> {
            ListView<ChatMessage> list = new ListView<>();
            ObservableList<ChatMessage> items = FXCollections.observableArrayList();
            long now = System.currentTimeMillis();
            for (int i = 0; i < MESSAGE_COUNT; i++) {
                boolean mine = i % 3 == 0;
                items.add(new ChatMessage(
                        "m" + i, "c" + i,
                        mine ? "10001" : "10002",
                        mine ? "Alice" : "小鲍",
                        "10001",
                        ChatType.PRIVATE, MsgType.TEXT,
                        "第 " + i + " 条消息，用来测试滚动是否顺畅，内容稍微长一点更接近真实情况。",
                        now - (MESSAGE_COUNT - i) * 1000L));
            }
            list.setItems(items);
            list.setCellFactory(view -> new MessageCell(
                    () -> "10001", list.widthProperty(), message -> new ContextMenu()));

            Stage stage = new Stage();
            StackPane root = new StackPane(list);
            stage.setScene(new Scene(root, 1000, 720));
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
                    if (frame[0] < FRAMES) {
                        list.scrollTo((frame[0] * 7) % MESSAGE_COUNT);
                        frame[0]++;
                    } else {
                        report(deltas);
                        Platform.exit();
                        System.exit(0);
                    }
                }
            }.start();
        });
    }

    private static void report(List<Double> deltas) {
        if (deltas.size() < 10) {
            System.out.println("样本太少");
            return;
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
        double average = sum / deltas.size();
        double p95 = sorted.get((int) (sorted.size() * 0.95));
        double max = sorted.get(sorted.size() - 1);
        System.out.printf("frames=%d avg=%.2fms p95=%.2fms max=%.2fms janky(>33ms)=%d%n",
                deltas.size(), average, p95, max, janky);
    }
}
