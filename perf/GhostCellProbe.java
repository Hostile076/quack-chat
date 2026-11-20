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
import javafx.scene.Node;
import javafx.scene.control.ListCell;
import javafx.scene.input.MouseButton;
import javafx.scene.robot.Robot;
import javafx.stage.Stage;

import java.util.ArrayList;
import java.util.List;

/**
 * 幽灵单元格回归测试。
 *
 * <p>曾经的 bug：ListCell 在构造时就 setGraphic，且空单元格被 setManaged(false)，
 * VirtualFlow 会把这种 cell 留在场景里不再更新 —— 表现为聊天区出现一堆
 * 「空头像 + 礼物卡 + 一条白条」的残影。
 */
public class GhostCellProbe {

    public static void main(String[] args) {
        Platform.startup(() -> {
            Stage stage = new Stage();
            AppWindow window = new AppWindow(stage, "ghost-probe", 900, 700, true);
            AppContext ctx = new AppContext(window);
            ChatPane pane = new ChatPane(ctx);
            window.setContent(pane, false);
            window.show();
            seed(ctx);
            var conversation = ctx.store().find(ChatType.PRIVATE, "10002");
            if (conversation != null) {
                pane.open(conversation);
            }

            after(900, () -> {
                // 滚一滚，逼 VirtualFlow 反复创建/回收 cell
                Robot robot = new Robot();
                var bounds = pane.localToScreen(pane.getBoundsInLocal());
                robot.mouseMove((int) (bounds.getMinX() + bounds.getWidth() / 2),
                        (int) (bounds.getMinY() + bounds.getHeight() / 2));
                robot.mousePress(MouseButton.PRIMARY);
                robot.mouseRelease(MouseButton.PRIMARY);
                for (int i = 0; i < 6; i++) {
                    robot.mouseWheel(3);
                }
                after(600, () -> {
                    inspect(pane);
                    Platform.exit();
                    System.exit(0);
                });
            });
        });
    }

    private static void inspect(ChatPane pane) {
        int cells = 0;
        int ghosts = 0;
        double minHeight = Double.MAX_VALUE;
        for (Node node : pane.lookupAll(".list-cell")) {
            if (!(node instanceof ListCell<?> cell)) {
                continue;
            }
            cells++;
            if (cell.getItem() == null && cell.getGraphic() != null) {
                ghosts++;
            }
            if (cell.getItem() != null && cell.getGraphic() != null) {
                minHeight = Math.min(minHeight, cell.getGraphic().getBoundsInLocal().getHeight());
            }
        }
        boolean ok = ghosts == 0 && (minHeight == Double.MAX_VALUE || minHeight > 24);
        System.out.printf("cells=%d ghosts=%d minGraphicHeight=%.1f%n", cells, ghosts,
                minHeight == Double.MAX_VALUE ? -1 : minHeight);
        System.out.println(ok ? "GHOST OK" : "GHOST FAILED");
        System.exit(ok ? 0 : 1);
    }

    private static void after(long millis, Runnable action) {
        new javafx.animation.Timeline(new javafx.animation.KeyFrame(
                javafx.util.Duration.millis(millis), e -> action.run())).play();
    }

    private static void seed(AppContext ctx) {
        List<Friend> friends = new ArrayList<>();
        friends.add(new Friend("10002", "小鲍", "小鲍", true));
        ctx.store().reset(new LoginResp(new User(10001L, "alice", "Alice", null),
                friends, new ArrayList<ChatGroup>()));

        var conversation = ctx.store().find(ChatType.PRIVATE, "10002");
        long now = System.currentTimeMillis();
        for (int i = 0; i < 120; i++) {
            boolean mine = i % 2 == 0;
            conversation.messages().add(new ChatMessage("m" + i, "c" + i,
                    mine ? "10001" : "10002",
                    mine ? "Alice" : "小鲍",
                    "10002", ChatType.PRIVATE, MsgType.TEXT,
                    "第 " + i + " 条消息，用来验证滚动时不会出现空壳单元格。", now - (120 - i) * 60_000L));
        }
    }
}
