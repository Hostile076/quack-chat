import io.quack.client.AppContext;
import io.quack.client.ui.AppWindow;
import io.quack.client.ui.ChatPane;
import io.quack.common.dto.LoginResp;
import io.quack.common.model.ChatGroup;
import io.quack.common.model.ChatMessage;
import io.quack.common.model.ChatType;
import io.quack.common.model.Friend;
import io.quack.common.model.MsgType;
import io.quack.common.model.User;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.stage.Stage;

import java.util.ArrayList;
import java.util.List;

/** 在真实 ChatPane 里塞礼物/文件消息，截图并打印尺寸 */
public class MessageListProbe {

    public static void main(String[] args) {
        Platform.startup(() -> {
            Stage stage = new Stage();
            AppWindow window = new AppWindow(stage, "msg-probe", 900, 700, true);
            AppContext ctx = new AppContext(window);
            seed(ctx);

            ChatPane pane = new ChatPane(ctx);
            window.setContent(pane, false);
            window.show();
            ctx.store().find(ChatType.PRIVATE, "10002");
            var conversation = ctx.store().find(ChatType.PRIVATE, "10002");
            if (conversation != null) {
                pane.open(conversation);
            }

            after(1200, () -> {
                dump(pane);
                capture(pane);
                Platform.exit();
                System.exit(0);
            });
        });
    }

    private static void dump(ChatPane pane) {
        for (javafx.scene.Node node : pane.lookupAll(".list-cell")) {
            if (node instanceof javafx.scene.control.ListCell<?> cell && cell.getGraphic() != null) {
                var g = cell.getGraphic();
                System.out.printf("cell h=%.1f graphic=%.1fx%.1f visible=%s%n",
                        cell.getHeight(), g.getBoundsInLocal().getWidth(),
                        g.getBoundsInLocal().getHeight(), g.isVisible());
                if (g instanceof javafx.scene.Parent parent) {
                    for (javafx.scene.Node child : parent.getChildrenUnmodifiable()) {
                        System.out.printf("   child %s %.1fx%.1f%n",
                                child.getClass().getSimpleName(),
                                child.getBoundsInLocal().getWidth(),
                                child.getBoundsInLocal().getHeight());
                    }
                }
            }
        }
    }

    private static void capture(ChatPane pane) {
        try {
            var screen = pane.localToScreen(pane.getBoundsInLocal());
            var shot = new java.awt.Robot().createScreenCapture(new java.awt.Rectangle(
                    (int) screen.getMinX(), (int) screen.getMinY(),
                    (int) screen.getWidth(), (int) screen.getHeight()));
            javax.imageio.ImageIO.write(shot, "png", new java.io.File("E:\\dsh_workspace\\chatcells.png"));
            System.out.println("captured");
        } catch (Exception ex) {
            System.out.println("capture failed: " + ex);
        }
    }

    private static void after(long millis, Runnable action) {
        new javafx.animation.Timeline(new javafx.animation.KeyFrame(
                javafx.util.Duration.millis(millis), e -> action.run())).play();
    }

    private static void seed(AppContext ctx) {
        List<Friend> friends = new ArrayList<>();
        friends.add(new Friend("10002", "Carol", "Carol", false));
        ctx.store().reset(new LoginResp(new User(10001L, "alice", "Alice", null),
                friends, new ArrayList<ChatGroup>()));

        var conversation = ctx.store().find(ChatType.PRIVATE, "10002");
        long now = System.currentTimeMillis();
        conversation.messages().add(new ChatMessage("g3", "gc3", "10002", "Carol", "10001",
                ChatType.PRIVATE, MsgType.GIFT, "rose", now - 60_000));
        conversation.messages().add(new ChatMessage("g4", "gc4", "10002", "Carol", "10001",
                ChatType.PRIVATE, MsgType.GIFT, "rose||", now - 55_000));
        conversation.messages().add(new ChatMessage("i1", "ic1", "10002", "Carol", "10001",
                ChatType.PRIVATE, MsgType.IMAGE, "http://127.0.0.1:9/none.png", now - 50_000));
        conversation.messages().add(new ChatMessage("g1", "gc1", "10002", "Carol", "10001",
                ChatType.PRIVATE, MsgType.GIFT, "rose|玫瑰|🌹", now - 40_000));
        conversation.messages().add(new ChatMessage("t1", "tc1", "10002", "Carol", "10001",
                ChatType.PRIVATE, MsgType.TEXT, "普通文本消息，用来对比高度", now - 30_000));
        conversation.messages().add(new ChatMessage("f1", "fc1", "10002", "Carol", "10001",
                ChatType.PRIVATE, MsgType.FILE, "http://127.0.0.1:1/a.pdf|报告.pdf|20480", now - 20_000));
        conversation.messages().add(new ChatMessage("g2", "gc2", "10001", "Alice", "10002",
                ChatType.PRIVATE, MsgType.GIFT, "cake|蛋糕|🍰", now - 10_000));
        // 畸形数据：只有 id / 空名称 / 空图标
        conversation.messages().add(new ChatMessage("g3", "gc3", "10002", "Carol", "10001",
                ChatType.PRIVATE, MsgType.GIFT, "rose", now - 5_000));
        conversation.messages().add(new ChatMessage("g4", "gc4", "10002", "Carol", "10001",
                ChatType.PRIVATE, MsgType.GIFT, "rose||", now - 4_000));
        conversation.messages().add(new ChatMessage("i1", "ic1", "10002", "Carol", "10001",
                ChatType.PRIVATE, MsgType.IMAGE, "http://127.0.0.1:9/none.png", now - 3_000));
    }
}
