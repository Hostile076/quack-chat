import io.quack.client.AppContext;
import io.quack.client.store.ClientStore;
import io.quack.client.ui.AppWindow;
import io.quack.client.ui.GroupInfoView;
import io.quack.client.ui.MainView;
import io.quack.common.dto.LoginResp;
import io.quack.common.model.ChatGroup;
import io.quack.common.model.ChatMessage;
import io.quack.common.model.ChatType;
import io.quack.common.model.Friend;
import io.quack.common.model.GroupDetail;
import io.quack.common.model.GroupMember;
import io.quack.common.model.MsgType;
import io.quack.common.model.User;
import javafx.application.Platform;
import javafx.stage.Stage;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** 视觉检查：未读红点位置、会话按新消息排序、群头像无方框 */
public class VisualProbe {

    public static void main(String[] args) throws Exception {
        var image = new java.awt.image.BufferedImage(200, 120,
                java.awt.image.BufferedImage.TYPE_INT_RGB);
        var g = image.createGraphics();
        g.setColor(new java.awt.Color(60, 120, 220));
        g.fillRect(0, 0, 200, 120);
        g.setColor(java.awt.Color.WHITE);
        g.setFont(new java.awt.Font("SansSerif", java.awt.Font.BOLD, 36));
        g.drawString("GROUP", 30, 75);
        g.dispose();
        File avatar = File.createTempFile("group-avatar", ".png");
        avatar.deleteOnExit();
        javax.imageio.ImageIO.write(image, "png", avatar);

        CountDownLatch started = new CountDownLatch(1);
        Platform.startup(() -> {
            Platform.setImplicitExit(false);
            started.countDown();
        });
        started.await(10, TimeUnit.SECONDS);

        CountDownLatch done = new CountDownLatch(1);
        Platform.runLater(() -> {
            Stage stage = new Stage();
            AppWindow window = new AppWindow(stage, "visual", 1000, 700, true);
            AppContext ctx = new AppContext(window);
            ClientStore store = ctx.store();

            List<Friend> friends = new ArrayList<>();
            friends.add(new Friend("10002", "小鲍", "小鲍", true));
            friends.add(new Friend("10003", "Carol", "Carol", false));
            List<ChatGroup> groups = new ArrayList<>();
            groups.add(new ChatGroup("q10001", "摸鱼小分队", 3, null));
            store.reset(new LoginResp(new User(10001L, "alice", "Alice", null), friends, groups));

            long now = System.currentTimeMillis();
            var bob = store.find(ChatType.PRIVATE, "10002");
            var carol = store.find(ChatType.PRIVATE, "10003");
            bob.messages().add(new ChatMessage("b1", "c1", "10002", "小鲍", "10001",
                    ChatType.PRIVATE, MsgType.TEXT, "刚发来的消息", now));
            carol.messages().add(new ChatMessage("c1", "c2", "10003", "Carol", "10001",
                    ChatType.PRIVATE, MsgType.TEXT, "很久以前", now - 86_400_000L));
            for (int i = 0; i < 3; i++) {
                bob.incrementUnread();
            }
            store.resort();

            MainView view = new MainView(ctx);
            window.setContent(view, false);
            window.show();

            after(1200, () -> {
                capture(stage, "E:\\dsh_workspace\\visual-main.png");
                stage.close();

                // 群资料页：带头像，检查有没有方框
                Stage stage2 = new Stage();
                AppWindow window2 = new AppWindow(stage2, "visual-group", 640, 520, true);
                AppContext ctx2 = new AppContext(window2);
                ctx2.store().reset(new LoginResp(new User(10001L, "alice", "Alice", null),
                        new ArrayList<>(), List.of(new ChatGroup("q10001", "摸鱼小分队", 3, null))));
                List<GroupMember> members = new ArrayList<>();
                members.add(new GroupMember("10001", "Alice", "OWNER"));
                GroupDetail detail = new GroupDetail("q10001", "摸鱼小分队", "10001", "",
                        1, members, avatar.toURI().toString());
                window2.setContent(GroupInfoView.preview(ctx2, ctx2.store().find(ChatType.GROUP, "q10001"),
                        detail), false);
                window2.show();
                after(1200, () -> {
                    capture(stage2, "E:\\dsh_workspace\\visual-group.png");
                    stage2.close();
                    done.countDown();
                });
            });
        });

        done.await(12, TimeUnit.SECONDS);
        Platform.exit();
        System.exit(0);
    }

    private static void capture(Stage stage, String path) {
        try {
            var shot = new java.awt.Robot().createScreenCapture(new java.awt.Rectangle(
                    (int) stage.getX(), (int) stage.getY(),
                    (int) stage.getWidth(), (int) stage.getHeight()));
            javax.imageio.ImageIO.write(shot, "png", new File(path));
            System.out.println("captured " + path);
        } catch (Exception error) {
            System.out.println("capture failed: " + error);
        }
    }

    private static void after(long millis, Runnable action) {
        new javafx.animation.Timeline(new javafx.animation.KeyFrame(
                javafx.util.Duration.millis(millis), e -> action.run())).play();
    }
}
