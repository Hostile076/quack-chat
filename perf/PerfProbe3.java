import javafx.animation.AnimationTimer;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

/** 隔离测试：空窗口在两种窗口样式下缩放，谁更平滑 */
public class PerfProbe3 {

    public static void main(String[] args) {
        Platform.startup(() -> run(StageStyle.TRANSPARENT, first ->
                run(StageStyle.UNDECORATED, second -> {
                    System.out.println("EMPTY-transparent  " + first);
                    System.out.println("EMPTY-undecorated  " + second);
                    Platform.exit();
                    System.exit(0);
                })));
    }

    private static void run(StageStyle style, Consumer<String> done) {
        Stage stage = new Stage(style);
        StackPane root = new StackPane(new Label("resize probe"));
        root.setStyle("-fx-background-color: white; -fx-background-radius: 14;");
        Scene scene = new Scene(root, 900, 640);
        if (style == StageStyle.TRANSPARENT) {
            scene.setFill(Color.TRANSPARENT);
        }
        stage.setScene(scene);
        stage.show();

        List<Double> deltas = new ArrayList<>();
        long[] last = {0};
        int[] frame = {0};
        new AnimationTimer() {
            @Override
            public void handle(long nanos) {
                if (last[0] != 0 && frame[0] >= 40) {
                    deltas.add((nanos - last[0]) / 1_000_000.0);
                }
                last[0] = nanos;
                if (frame[0] < 340) {
                    stage.setWidth(880 + (frame[0] % 60) * 4);
                    stage.setHeight(620 + (frame[0] % 40) * 3);
                    frame[0]++;
                } else {
                    stop();
                    stage.close();
                    done.accept(summary(deltas));
                }
            }
        }.start();
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
        return String.format("avg=%.2fms p95=%.2fms max=%.2fms janky=%d",
                sum / deltas.size(), sorted.get((int) (sorted.size() * 0.95)),
                sorted.get(sorted.size() - 1), janky);
    }
}
