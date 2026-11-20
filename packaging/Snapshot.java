import io.quack.client.AppContext;
import io.quack.client.ui.AppWindow;
import io.quack.client.ui.FriendRequestPane;
import io.quack.client.ui.GroupInfoView;
import io.quack.client.ui.ProfileView;
import io.quack.client.ui.LoginView;
import io.quack.client.ui.MainView;
import io.quack.common.dto.LoginResp;
import io.quack.common.model.ChatGroup;
import io.quack.common.model.ChatMessage;
import io.quack.common.model.ChatType;
import io.quack.common.model.Friend;
import io.quack.common.model.FriendRequest;
import io.quack.common.model.GroupDetail;
import io.quack.common.model.GroupMember;
import io.quack.common.model.UserProfile;
import io.quack.common.model.MsgType;
import io.quack.common.model.User;
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
 * 离屏渲染界面截图（含无边框窗口外壳）：README 配图 + “界面没崩”的冒烟检查。
 *
 * <p>用法：{@code java Snapshot.java <输出目录>}
 */
public class Snapshot {

    public static void main(String[] args) throws Exception {
        Path outDir = Path.of(args.length > 0 ? args[0] : "screenshots");
        Files.createDirectories(outDir);

        CountDownLatch done = new CountDownLatch(1);
        Platform.startup(() -> {
            try {
                // 登录页
                AppWindow loginWindow = new AppWindow(new Stage(), "Quack Chat", 420, 560, false);
                AppContext loginCtx = new AppContext(loginWindow);
                loginWindow.setContent(new LoginView(loginCtx), false);
                render(loginWindow, outDir.resolve("login.png"));

                // 注册页（只能填用户名 + 密码）
                AppWindow registerWindow = new AppWindow(new Stage(), "Quack Chat", 420, 560, false);
                AppContext registerCtx = new AppContext(registerWindow);
                LoginView registerView = new LoginView(registerCtx);
                registerView.showRegisterMode();
                registerWindow.setContent(registerView, false);
                render(registerWindow, outDir.resolve("register.png"));

                // 主界面（正常尺寸）
                AppWindow mainWindow = new AppWindow(new Stage(), "Quack Chat", 1000, 660, true);
                AppContext ctx = new AppContext(mainWindow);
                seed(ctx);
                mainWindow.setContent(new MainView(ctx), false);
                render(mainWindow, outDir.resolve("main.png"));

                // 主界面（缩小，验证自适应）
                AppWindow smallWindow = new AppWindow(new Stage(), "Quack Chat", 820, 560, true);
                AppContext smallCtx = new AppContext(smallWindow);
                seed(smallCtx);
                smallWindow.setContent(new MainView(smallCtx), false);
                render(smallWindow, outDir.resolve("main-small.png"));

                // 「新的朋友」页面
                AppWindow requestWindow = new AppWindow(new Stage(), "Quack Chat", 900, 620, true);
                AppContext requestCtx = new AppContext(requestWindow);
                seed(requestCtx);
                requestWindow.setContent(new FriendRequestPane(requestCtx), false);
                render(requestWindow, outDir.resolve("requests.png"));

                // 个人主页
                AppWindow profileWindow = new AppWindow(new Stage(), "个人主页", 560, 800, false);
                AppContext profileCtx = new AppContext(profileWindow);
                seed(profileCtx);
                UserProfile profile = new UserProfile(10002L, "bob", "小鲍",
                        "如果常常流泪，就不能看见星星。", "男", "2003-04-08", "江苏 徐州", "学生", 1572,
                        System.currentTimeMillis() - 86_400_000L * 200);
                profileWindow.setContent(ProfileView.preview(profileCtx, profile), false);
                render(profileWindow, outDir.resolve("profile.png"));

                // 群主页
                AppWindow groupWindow = new AppWindow(new Stage(), "聊天信息", 620, 880, false);
                AppContext groupCtx = new AppContext(groupWindow);
                seed(groupCtx);
                GroupDetail detail = new GroupDetail("q10001", "摸鱼小分队", "10001",
                        "本周五晚八点，一起过一遍服务端代码。", 3,
                        List.of(new GroupMember("10001", "Alice", GroupMember.OWNER),
                                new GroupMember("10002", "小鲍", GroupMember.MEMBER),
                                new GroupMember("10003", "Carol", GroupMember.MEMBER)));
                groupWindow.setContent(GroupInfoView.preview(groupCtx,
                        groupCtx.store().find(ChatType.GROUP, "q10001"), detail), false);
                render(groupWindow, outDir.resolve("group-info.png"));

                System.out.println("snapshots written to " + outDir.toAbsolutePath());
            } catch (Throwable t) {
                t.printStackTrace();
            } finally {
                done.countDown();
            }
        });
        if (!done.await(40, TimeUnit.SECONDS)) {
            System.out.println("snapshot timeout");
        }
        Platform.exit();
        System.exit(0);
    }

    private static void render(AppWindow window, Path out) throws Exception {
        Parent root = window.root();
        Scene scene = root.getScene();
        root.applyCss();
        ((Region) root).resize(scene.getWidth(), scene.getHeight());
        root.layout();

        WritableImage image = new WritableImage((int) Math.round(scene.getWidth()),
                (int) Math.round(scene.getHeight()));
        root.snapshot(null, image);
        ImageIO.write(toBufferedImage(image), "png", new File(out.toString()));
        System.out.println("wrote " + out);
    }

    /** 造一份演示数据，让主界面看起来像真的在用 */
    private static void seed(AppContext ctx) {
        User me = new User(10001L, "alice", "Alice", null);
        List<Friend> friends = List.of(
                new Friend("10002", "小鲍", "小鲍", true),
                new Friend("10003", "Carol", "Carol", false),
                new Friend("10004", "Dave", "Dave", true));
        List<ChatGroup> groups = List.of(
                new ChatGroup("q10001", "摸鱼小分队", 3),
                new ChatGroup("q10002", "Java 自学小组", 2));
        ctx.store().reset(new LoginResp(me, friends, groups));

        long now = System.currentTimeMillis();
        var bob = ctx.store().find(ChatType.PRIVATE, "10002");
        if (bob != null) {
            bob.messages().add(new ChatMessage("m1", "c1", "10002", "小鲍", "10001",
                    ChatType.PRIVATE, MsgType.TEXT, "晚上一起联调？", now - 900_000));
            bob.messages().add(new ChatMessage("m2", "c2", "10001", "Alice", "10002",
                    ChatType.PRIVATE, MsgType.TEXT, "行，服务端我已经起好了", now - 840_000));
            bob.messages().add(new ChatMessage("m3", "c3", "10002", "小鲍", "10001",
                    ChatType.PRIVATE, MsgType.TEXT, "那我注册个账号加你好友 👍", now - 600_000));
            bob.messages().add(new ChatMessage("m4", "c4", "10001", "Alice", "10002",
                    ChatType.PRIVATE, MsgType.TEXT, "好，加完直接发我消息就行", now - 560_000));
        }
        var group = ctx.store().find(ChatType.GROUP, "q10001");
        if (group != null) {
            group.messages().add(new ChatMessage("g1", "x1", "10003", "Carol", "q10001",
                    ChatType.GROUP, MsgType.TEXT, "周末有人一起改 bug 吗", now - 120_000));
            group.messages().add(new ChatMessage("g2", "x2", "10002", "小鲍", "q10001",
                    ChatType.GROUP, MsgType.TEXT, "算我一个", now - 60_000));
        }
        ctx.store().find(ChatType.PRIVATE, "10004");
        ctx.store().incomingRequests().add(new FriendRequest("r1", "10005", "Dave", "10001",
                "你好，我是 Dave，在你的群里看到你的号", now - 120_000, FriendRequest.PENDING));
        ctx.store().incomingRequests().add(new FriendRequest("r2", "10006", "Eve", "10001",
                "交个朋友？", now - 30_000, FriendRequest.PENDING));

        var dave = ctx.store().find(ChatType.PRIVATE, "10004");
        if (dave != null) {
            dave.messages().add(new ChatMessage("d1", "y1", "10004", "Dave", "10001",
                    ChatType.PRIVATE, MsgType.TEXT, "hi，刚注册的号", now - 3_600_000));
        }
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
