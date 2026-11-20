import io.quack.client.media.AudioCall;
import io.quack.client.store.ClientStore;
import io.quack.client.ui.ImageViewerDialog;
import io.quack.client.ui.ModalShell;
import io.quack.common.dto.LoginResp;
import io.quack.common.model.ChatGroup;
import io.quack.common.model.ChatType;
import io.quack.common.model.Friend;
import io.quack.common.model.User;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/** 麦克风 / 弹窗定位 / 置顶排序 / 图片查看器 四项验证 */
public class UiFeaturesProbe {

    private static int failures;

    public static void main(String[] args) {
        // 1. 麦克风（不需要 JavaFX）
        AudioCall call = new AudioCall(frame -> {
        });
        String micError = call.start();
        if (micError == null) {
            System.out.println("PASS 麦克风可用（16kHz 单声道），1 秒后关闭");
        } else {
            System.out.println("INFO 本机麦克风不可用: " + micError);
        }
        call.close();

        Platform.startup(() -> {
            // 探针会反复开/关窗口，关掉"最后一个窗口关闭就退出"的行为
            Platform.setImplicitExit(false);
            pinTest();
            popupTest(() -> viewerTest(() -> {
                System.out.println(failures == 0 ? "UIFEATURES OK" : "UIFEATURES FAILED: " + failures);
                Platform.exit();
                System.exit(failures == 0 ? 0 : 1);
            }));
        });
    }

    // ------------------------------------------------------------------

    private static void pinTest() {
        ClientStore store = new ClientStore();
        List<Friend> friends = new ArrayList<>();
        friends.add(new Friend("10002", "B", "B", true));
        friends.add(new Friend("10003", "C", "C", true));
        friends.add(new Friend("10004", "A", "A", true));
        store.reset(new LoginResp(new User(10001L, "alice", "Alice", null), friends,
                new ArrayList<ChatGroup>()));
        // 先清掉历史遗留的置顶状态，否则测试数据会被上一次运行污染
        store.conversations().forEach(c -> store.prefs().setPinned(c.key(), false));
        store.resort();
        String first = store.conversations().get(0).getTitle();
        store.prefs().setPinned(ChatType.PRIVATE.name() + ":10003", true);
        store.resort();

        String after = store.conversations().get(0).getTitle();
        boolean ok = "C".equals(after) && !"C".equals(first);
        if (!ok) {
            failures++;
        }
        System.out.println((ok ? "PASS" : "FAIL") + " 置顶排序把 C 提到了第一位（置顶前第一是 " + first + "）");
    }

    // ------------------------------------------------------------------

    private static void popupTest(Runnable done) {
        Stage host = new Stage();
        Button anchor = new Button("锚点按钮");
        StackPane root = new StackPane(anchor);
        host.setScene(new Scene(root, 800, 600));
        host.setX(400);
        host.setY(400);
        host.show();
        root.applyCss();
        root.layout();

        ModalShell shell = new ModalShell(host, "popup", 280, 160);
        shell.setContent(new Label("弹窗内容"));
        shell.showAbove(anchor);

        after(500, () -> {
            var anchorBounds = anchor.localToScreen(anchor.getBoundsInLocal());
            double popupY = shell.stage().getY();
            boolean ok = anchorBounds != null && popupY < anchorBounds.getMinY();
            if (!ok) {
                failures++;
            }
            System.out.printf("%s 弹窗出现在按钮上方（按钮 y=%.0f，弹窗 y=%.0f）%n",
                    ok ? "PASS" : "FAIL",
                    anchorBounds == null ? -1 : anchorBounds.getMinY(), popupY);
            shell.close();
            host.close();
            after(300, done);
        });
    }

    // ------------------------------------------------------------------

    private static void viewerTest(Runnable done) {
        Stage host = new Stage();
        host.setScene(new Scene(new StackPane(), 300, 200));
        host.setX(300);
        host.setY(300);
        host.show();

        String url = new java.io.File("E:\\dsh_workspace\\quack-chat\\docs\\screenshots\\main.png")
                .toURI().toString();
        Platform.runLater(() -> ImageViewerDialog.show(host, url));

        after(1400, () -> {
            boolean found = false;
            try {
            for (Window window : new java.util.ArrayList<>(Window.getWindows())) {
                if (window instanceof Stage stage && "查看图片".equals(stage.getTitle()) && stage.isShowing()) {
                    found = true;
                    try {
                        var bounds = stage.getX() + "," + stage.getY();
                        var shot = new java.awt.Robot().createScreenCapture(new java.awt.Rectangle(
                                (int) stage.getX(), (int) stage.getY(),
                                (int) stage.getWidth(), (int) stage.getHeight()));
                        javax.imageio.ImageIO.write(shot, "png",
                                new java.io.File("E:\\dsh_workspace\\viewer.png"));
                        System.out.println("PASS 图片查看器已打开 @" + bounds);
                    } catch (Exception ex) {
                        System.out.println("INFO 截图失败: " + ex);
                    }
                    stage.close();
                }
            }
            } catch (Throwable error) {
                System.out.println("INFO 查看器检查异常: " + error);
            }
            if (!found) {
                failures++;
                System.out.println("FAIL 图片查看器没有打开");
            }
            host.close();
            after(300, done);
        });
    }

    private static void after(long millis, Runnable action) {
        new javafx.animation.Timeline(new javafx.animation.KeyFrame(
                javafx.util.Duration.millis(millis), e -> action.run())).play();
    }
}
