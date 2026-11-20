import javafx.animation.AnimationTimer;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.robot.Robot;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

import java.util.ArrayList;
import java.util.List;

/** 空窗口 + Robot 拖右边缘：测这台机器窗口缩放的物理上限 */
public class EmptyDragProbe {

    public static void main(String[] args) {
        Platform.startup(() -> {
            Stage stage = new Stage(StageStyle.TRANSPARENT);
            StackPane root = new StackPane(new Label("empty"));
            root.setStyle("-fx-background-color: white; -fx-background-radius: 14;");
            Scene scene = new Scene(root, 900, 700);
            scene.setFill(Color.TRANSPARENT);
            stage.setScene(scene);
            stage.show();

            javafx.animation.Timeline wait = new javafx.animation.Timeline(
                    new javafx.animation.KeyFrame(javafx.util.Duration.millis(700), e -> {
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
                                    double sum = 0;
                                    double max = 0;
                                    for (double d : deltas) {
                                        sum += d;
                                        max = Math.max(max, d);
                                    }
                                    double avg = sum / deltas.size();
                                    System.out.printf("EMPTY-RESIZE avg=%.2fms => %.0f FPS max=%.2fms%n",
                                            avg, 1000.0 / avg, max);
                                    Platform.exit();
                                    System.exit(0);
                                }
                            }
                        }.start();
                    }));
            wait.play();
        });
    }
}
