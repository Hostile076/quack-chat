import io.quack.client.ui.MessageCell;
import io.quack.common.model.ChatMessage;
import io.quack.common.model.ChatType;
import io.quack.common.model.MsgType;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyDoubleWrapper;
import javafx.collections.FXCollections;
import javafx.scene.Scene;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.ListView;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.util.ArrayList;
import java.util.List;

/** 复现：礼品/文件/图片/文本在列表里的渲染尺寸 */
public class MessageCellProbe {

    public static void main(String[] args) {
        Platform.startup(() -> {
            ReadOnlyDoubleWrapper bubbleWidth = new ReadOnlyDoubleWrapper(420);

            List<ChatMessage> messages = new ArrayList<>();
            long now = System.currentTimeMillis();
            messages.add(new ChatMessage("1", "c1", "10002", "Carol", "10001",
                    ChatType.PRIVATE, MsgType.GIFT, "rose|玫瑰|🌹", now));
            messages.add(new ChatMessage("2", "c2", "10002", "Carol", "10001",
                    ChatType.PRIVATE, MsgType.TEXT, "这是一条普通文本消息", now));
            messages.add(new ChatMessage("3", "c3", "10002", "Carol", "10001",
                    ChatType.PRIVATE, MsgType.FILE, "http://x/a.pdf|报告.pdf|20480", now));

            ListView<ChatMessage> list = new ListView<>(FXCollections.observableArrayList(messages));
            list.setCellFactory(view -> new MessageCell(() -> "10001", bubbleWidth,
                    message -> new ContextMenu()));
            list.setFixedCellSize(-1);

            Stage stage = new Stage();
            Scene scene = new Scene(new StackPane(list), 760, 620);
            // 关键：带上真实样式表，否则测不出问题
            scene.getStylesheets().add(MessageCell.class.getResource("/app.css").toExternalForm());
            stage.setScene(scene);
            stage.show();

            bubbleWidth.set(420);

            javafx.animation.Timeline timeline = new javafx.animation.Timeline(
                    new javafx.animation.KeyFrame(javafx.util.Duration.millis(900), e -> {
                        for (javafx.scene.Node cell : list.lookupAll(".list-cell")) {
                            var bounds = cell.getBoundsInLocal();
                            System.out.printf("cell %s  h=%.1f  w=%.1f%n",
                                    cell.toString().contains("MessageCell") ? "msg" : "?",
                                    bounds.getHeight(), bounds.getWidth());
                        }
                        try {
                            var screen = list.localToScreen(list.getBoundsInLocal());
                            var shot = new java.awt.Robot().createScreenCapture(new java.awt.Rectangle(
                                    (int) screen.getMinX(), (int) screen.getMinY(),
                                    (int) screen.getWidth(), (int) screen.getHeight()));
                            javax.imageio.ImageIO.write(shot, "png",
                                    new java.io.File("E:\\dsh_workspace\\cells.png"));
                        } catch (Exception ex) {
                            System.out.println("shot failed: " + ex);
                        }
                        for (javafx.scene.Node cell : list.lookupAll(".list-cell")) {
                            if (cell instanceof javafx.scene.control.ListCell<?> lc && lc.getGraphic() != null) {
                                var g = lc.getGraphic();
                                System.out.printf("  graphic %.1f x %.1f, children=%d%n",
                                        g.getBoundsInLocal().getWidth(), g.getBoundsInLocal().getHeight(),
                                        ((javafx.scene.Parent) g).getChildrenUnmodifiable().size());
                            }
                        }
                        Platform.exit();
                        System.exit(0);
                    }));
            timeline.play();
        });
    }
}
