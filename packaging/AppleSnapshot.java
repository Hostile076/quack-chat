import io.quack.client.AppContext;
import io.quack.client.ui.AppWindow;
import io.quack.client.ui.GroupInfoView;
import io.quack.client.ui.LoginView;
import io.quack.client.ui.MainView;
import io.quack.client.ui.ProfileView;
import io.quack.common.dto.LoginResp;
import io.quack.common.model.ChatGroup;
import io.quack.common.model.ChatMessage;
import io.quack.common.model.ChatType;
import io.quack.common.model.Friend;
import io.quack.common.model.FriendRequest;
import io.quack.common.model.GroupDetail;
import io.quack.common.model.GroupMember;
import io.quack.common.model.MsgType;
import io.quack.common.model.User;
import io.quack.common.model.UserProfile;
import javafx.application.Platform;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.Region;
import javafx.stage.Stage;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Apple 风格改版的离屏截图：浅色 + 深色两套，验证令牌主题与布局。
 *
 * <p>用法：{@code java AppleSnapshot.java <输出目录>}
 */
public class AppleSnapshot {

    public static void main(String[] args) throws Exception {
        Path outDir = Path.of(args.length > 0 ? args[0] : "screenshots");
        Files.createDirectories(outDir);

        CountDownLatch done = new CountDownLatch(1);
        Platform.startup(() -> {
            try {
                AppWindow loginWindow = new AppWindow(new Stage(), "Quack Chat", 380, 600, false);
                loginWindow.setTitleBarVisible(false);
                AppContext loginCtx = new AppContext(loginWindow);
                loginWindow.setContent(new LoginView(loginCtx), false);
                render(loginWindow, outDir.resolve("apple-login.png"), false);
                render(loginWindow, outDir.resolve("apple-login-dark.png"), true);

                // 最小尺寸下的登录页：确认内容不会被裁
                AppWindow smallLogin = new AppWindow(new Stage(), "Quack Chat", 340, 570, false);
                smallLogin.setTitleBarVisible(false);
                AppContext smallLoginCtx = new AppContext(smallLogin);
                smallLogin.setContent(new LoginView(smallLoginCtx), false);
                render(smallLogin, outDir.resolve("apple-login-small.png"), false);
                AppWindow mainWindow = new AppWindow(new Stage(), "Quack Chat", 1080, 700, true);
                AppContext ctx = new AppContext(mainWindow);
                seed(ctx);
                mainWindow.setContent(new MainView(ctx), false);
                render(mainWindow, outDir.resolve("apple-main.png"), false);
                render(mainWindow, outDir.resolve("apple-main-dark.png"), true);

                AppWindow profileWindow = new AppWindow(new Stage(), "个人主页", 560, 800, false);
                AppContext profileCtx = new AppContext(profileWindow);
                seed(profileCtx);
                UserProfile profile = new UserProfile(10002L, "演示账号2", "演示账号2",
                        "如果常常流泪，就不能看见星星。", "男", "2003-04-08", "江苏 徐州", "学生", 1572,
                        System.currentTimeMillis() - 86_400_000L * 200);
                profileWindow.setContent(ProfileView.preview(profileCtx, profile), false);
                render(profileWindow, outDir.resolve("apple-profile.png"), false);

                AppWindow groupWindow = new AppWindow(new Stage(), "聊天信息", 620, 880, false);
                AppContext groupCtx = new AppContext(groupWindow);
                seed(groupCtx);
                GroupDetail detail = new GroupDetail("q10001", "摸鱼小分队", "10001",
                        "本周五晚八点，一起过一遍服务端代码。", 3,
                        List.of(new GroupMember("10001", "演示账号1", GroupMember.OWNER),
                                new GroupMember("10002", "演示账号2", GroupMember.MEMBER),
                                new GroupMember("10003", "演示账号3", GroupMember.MEMBER)));
                groupWindow.setContent(GroupInfoView.preview(groupCtx,
                        groupCtx.store().find(ChatType.GROUP, "q10001"), detail), false);
                render(groupWindow, outDir.resolve("apple-group.png"), false);

                System.out.println("snapshots written to " + outDir.toAbsolutePath());
            } catch (Throwable t) {
                t.printStackTrace();
            } finally {
                done.countDown();
            }
        });
        if (!done.await(60, TimeUnit.SECONDS)) {
            System.out.println("snapshot timeout");
        }
        Platform.exit();
        System.exit(0);
    }

    private static void render(AppWindow window, Path out, boolean dark) throws Exception {
        Parent root = window.root();
        Scene scene = root.getScene();
        if (dark) {
            if (!root.getStyleClass().contains("dark")) {
                root.getStyleClass().add("dark");
            }
        } else {
            root.getStyleClass().remove("dark");
        }
        root.applyCss();
        ((Region) root).resize(scene.getWidth(), scene.getHeight());
        root.layout();
        // 让弹簧/图片加载完成一帧
        Thread.sleep(120);
        root.applyCss();
        root.layout();

        WritableImage image = new WritableImage((int) Math.round(scene.getWidth()),
                (int) Math.round(scene.getHeight()));
        root.snapshot(null, image);
        ImageIO.write(toBufferedImage(image), "png", new File(out.toString()));
        System.out.println("wrote " + out);
    }

    private static void seed(AppContext ctx) {
        User me = new User(10001L, "演示账号1", "演示账号1", null);
        List<Friend> friends = List.of(
                new Friend("10002", "演示账号2", "演示账号2", true),
                new Friend("10003", "演示账号3", "演示账号3", false),
                new Friend("10004", "演示账号4", "演示账号4", true),
                new Friend("10005", "演示账号5", "演示账号5", false));
        List<ChatGroup> groups = List.of(
                new ChatGroup("q10001", "摸鱼小分队", 3),
                new ChatGroup("q10002", "Java 自学小组", 2));
        ctx.store().reset(new LoginResp(me, friends, groups));

        long now = System.currentTimeMillis();
        var 演示账号2 = ctx.store().find(ChatType.PRIVATE, "10002");
        if (演示账号2 != null) {
            演示账号2.messages().add(new ChatMessage("m0", "c0", "system", "系统", "10002",
                    ChatType.PRIVATE, MsgType.SYSTEM, "你已添加了演示账号2，现在可以开始聊天了", now - 1_000_000));
            演示账号2.messages().add(new ChatMessage("m1", "c1", "10002", "演示账号2", "10001",
                    ChatType.PRIVATE, MsgType.TEXT, "晚上一起联调？", now - 900_000));
            演示账号2.messages().add(new ChatMessage("m2", "c2", "10001", "演示账号1", "10002",
                    ChatType.PRIVATE, MsgType.TEXT, "行，服务端我已经起好了", now - 840_000));
            演示账号2.messages().add(new ChatMessage("m3", "c3", "10002", "演示账号2", "10001",
                    ChatType.PRIVATE, MsgType.TEXT, "那我注册个账号加你好友 👍", now - 600_000));
            演示账号2.messages().add(new ChatMessage("m4", "c4", "10001", "演示账号1", "10002",
                    ChatType.PRIVATE, MsgType.TEXT, "好，加完直接发我消息就行", now - 560_000));
            演示账号2.messages().add(new ChatMessage("m5", "c5", "10002", "演示账号2", "10001",
                    ChatType.PRIVATE, MsgType.FILE, "https://example.com/design.fig|设计稿.fig|2411724|",
                    now - 420_000));
            演示账号2.messages().add(new ChatMessage("m6", "c6", "10001", "演示账号1", "10002",
                    ChatType.PRIVATE, MsgType.GIFT, "rose|玫瑰|🌹", now - 360_000));
            演示账号2.messages().add(new ChatMessage("m7", "c7", "10002", "演示账号2", "10001",
                    ChatType.PRIVATE, MsgType.TEXT, "新的界面看起来清爽多了", now - 1000));
        }
        var group = ctx.store().find(ChatType.GROUP, "q10001");
        if (group != null) {
            group.messages().add(new ChatMessage("g1", "x1", "10003", "演示账号3", "q10001",
                    ChatType.GROUP, MsgType.TEXT, "周末有人一起改 bug 吗", now - 120_000));
            group.messages().add(new ChatMessage("g2", "x2", "10002", "演示账号2", "q10001",
                    ChatType.GROUP, MsgType.TEXT, "算我一个", now - 60_000));
        }
        ctx.store().incomingRequests().add(new FriendRequest("r1", "10005", "演示账号4", "10001",
                "你好，我是 演示账号4，在你的群里看到你的号", now - 120_000, FriendRequest.PENDING));
        ctx.store().incomingRequests().add(new FriendRequest("r2", "10006", "演示账号5", "10001",
                "交个朋友？", now - 30_000, FriendRequest.PENDING));
        // 按最近消息重排，让截图默认落在有内容的演示账号2会话
        ctx.store().resort();
    }

    private static BufferedImage toBufferedImage(WritableImage image) {
        int width = (int) image.getWidth();
        int height = (int) image.getHeight();
        BufferedImage buffered = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        PixelReader reader = image.getPixelReader();
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                buffered.setRGB(x, y, reader.getArgb(x, y));
            }
        }
        return buffered;
    }
}
