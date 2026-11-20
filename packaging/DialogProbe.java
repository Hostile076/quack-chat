import io.quack.client.AppContext;
import io.quack.client.ui.AppWindow;
import io.quack.client.ui.GroupInfoView;
import io.quack.client.ui.MainView;
import io.quack.client.ui.ProfileView;
import io.quack.common.dto.LoginResp;
import io.quack.common.model.ChatGroup;
import io.quack.common.model.ChatType;
import io.quack.common.model.Friend;
import io.quack.common.model.User;
import javafx.application.Platform;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.util.ArrayList;
import java.util.List;

/**
 * 弹窗可见性回归测试。
 *
 * <p>专门防住那个 bug：弹窗被设成 opacity=0（看不见）却仍然 modal，把主窗口锁死。
 * 只要任何一个弹窗的透明度不是 1，就判失败。
 */
public class DialogProbe {

    private static int failures;

    public static void main(String[] args) {
        Platform.startup(() -> {
            Stage stage = new Stage();
            AppWindow window = new AppWindow(stage, "probe", 1000, 700, true);
            AppContext ctx = new AppContext(window);
            seed(ctx);
            window.setContent(new MainView(ctx), false);
            window.show();

            after(700, () -> {
                ProfileView.show(stage, ctx, "10002", null);
                after(900, () -> {
                    check("个人主页", 2);
                    closeDialogs(stage);
                    after(500, () -> {
                        var conversation = ctx.store().find(ChatType.GROUP, "q10001");
                        GroupInfoView.show(stage, ctx, conversation, () -> {
                        }, text -> {
                        });
                        after(900, () -> {
                            check("群主页", 2);
                            closeDialogs(stage);
                            after(300, () -> {
                                System.out.println(failures == 0 ? "DIALOGS OK" : "DIALOGS FAILED: " + failures);
                                Platform.exit();
                                System.exit(failures == 0 ? 0 : 1);
                            });
                        });
                    });
                });
            });
        });
    }

    private static void check(String label, int expectedWindows) {
        List<Window> showing = new ArrayList<>();
        for (Window w : Window.getWindows()) {
            if (w.isShowing()) {
                showing.add(w);
            }
        }
        System.out.println("[" + label + "] 可见窗口 " + showing.size() + " 个");
        for (Window w : showing) {
            double opacity = w.getOpacity();
            System.out.printf("   title=%-28s opacity=%.2f modality=%s%n",
                    ((Stage) w).getTitle(), opacity, ((Stage) w).getModality());
            if (opacity < 0.99) {
                failures++;
                System.out.println("   [FAIL] 窗口不可见（opacity<1）但会锁住主窗口");
            }
        }
        if (showing.size() != expectedWindows) {
            failures++;
            System.out.println("   [FAIL] 期望 " + expectedWindows + " 个窗口，实际 " + showing.size());
        }
    }

    private static void closeDialogs(Stage main) {
        for (Window w : new ArrayList<>(Window.getWindows())) {
            if (w instanceof Stage s && s != main && w.isShowing()) {
                s.close();
            }
        }
    }

    private static void after(long millis, Runnable action) {
        javafx.animation.Timeline timeline = new javafx.animation.Timeline(
                new javafx.animation.KeyFrame(javafx.util.Duration.millis(millis),
                        e -> action.run()));
        timeline.play();
    }

    private static void seed(AppContext ctx) {
        List<Friend> friends = new ArrayList<>();
        friends.add(new Friend("10002", "小鲍", "小鲍", true));
        List<ChatGroup> groups = new ArrayList<>();
        groups.add(new ChatGroup("q10001", "摸鱼小分队", 3));
        ctx.store().reset(new LoginResp(new User(10001L, "alice", "Alice", null), friends, groups));

    }
}
