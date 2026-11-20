import io.quack.client.AppContext;
import io.quack.client.ui.AppWindow;
import io.quack.client.ui.ContactsPane;
import io.quack.client.ui.FriendRequestPane;
import io.quack.common.dto.LoginResp;
import io.quack.common.model.ChatGroup;
import io.quack.common.model.Friend;
import io.quack.common.model.FriendRequest;
import io.quack.common.model.User;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.image.ImageView;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * 好友头像回归：
 * <ol>
 *   <li>登录响应里带了好友头像时，会话/通讯录要能显示出来；</li>
 *   <li>好友申请列表也要显示申请人头像（fromAvatar）。</li>
 * </ol>
 */
public class AvatarListProbe {

    private static int passed;
    private static final List<String> failures = new ArrayList<>();
    private static String avatarUri;

    public static void main(String[] args) throws Exception {
        CompletableFuture<Void> started = new CompletableFuture<>();
        Platform.startup(() -> started.complete(null));
        started.get(20, TimeUnit.SECONDS);

        // 生成一张本地头像文件，用 file: URL 模拟服务端头像地址
        java.nio.file.Path image = java.nio.file.Files.createTempFile("avatar-list", ".png");
        java.awt.image.BufferedImage buffered =
                new java.awt.image.BufferedImage(96, 96, java.awt.image.BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D graphics = buffered.createGraphics();
        graphics.setColor(java.awt.Color.BLUE);
        graphics.fillRect(0, 0, 96, 96);
        graphics.dispose();
        javax.imageio.ImageIO.write(buffered, "png", image.toFile());
        avatarUri = image.toUri().toString();

        Stage stage = fx(Stage::new);
        AppWindow window = fx(() -> new AppWindow(stage, "头像列表探针", 560, 820, true));
        AppContext ctx = fx(() -> new AppContext(window));

        try {
            check("通讯录显示好友头像", () -> {
                Node pane = fx(() -> {
                    ctx.store().reset(new LoginResp(
                            new User(10001L, "演示账号1", "演示账号1", avatarUri),
                            List.of(new Friend("10002", "演示账号2", "演示账号2", true, avatarUri)),
                            List.of(new ChatGroup("q10001", "摸鱼小分队", 2, null))));
                    ContactsPane contacts = new ContactsPane(ctx, conversation -> {
                    }, () -> {
                    });
                    StackPane root = new StackPane(contacts);
                    root.getStyleClass().add("root");
                    Scene scene = new Scene(root, 480, 600);
                    scene.getStylesheets().add(
                            AvatarListProbe.class.getResource("/app.css").toExternalForm());
                    stage.setScene(scene);
                    stage.show();
                    root.applyCss();
                    root.layout();
                    return contacts;
                });
                Thread.sleep(500);
                String url = fx(() -> {
                    for (Node row : pane.lookupAll(".contact-row")) {
                        String found = imageUrlOf(row);
                        if (found != null) {
                            return found;
                        }
                    }
                    return null;
                });
                assertThat(url != null && url.startsWith("file:"), "通讯录头像没有加载: " + url);
                assertThat(url.contains("/avatar-list"), "通讯录头像 URL 不对: " + url);
            });

            check("好友申请显示申请人头像", () -> {
                Node pane = fx(() -> {
                    ctx.store().incomingRequests().setAll(List.of(new FriendRequest(
                            "r1", "10003", "演示账号3", "10001", "你好", System.currentTimeMillis(),
                            FriendRequest.PENDING, null, avatarUri)));
                    FriendRequestPane requests = new FriendRequestPane(ctx);
                    StackPane root = new StackPane(requests);
                    root.getStyleClass().add("root");
                    Scene scene = new Scene(root, 480, 600);
                    scene.getStylesheets().add(
                            AvatarListProbe.class.getResource("/app.css").toExternalForm());
                    stage.setScene(scene);
                    root.applyCss();
                    root.layout();
                    return requests;
                });
                Thread.sleep(500);
                String url = fx(() -> {
                    for (Node row : pane.lookupAll(".request-row")) {
                        String found = imageUrlOf(row);
                        if (found != null) {
                            return found;
                        }
                    }
                    return null;
                });
                assertThat(url != null && url.startsWith("file:"), "申请列表头像没有加载: " + url);
            });
        } catch (Throwable error) {
            failures.add("未捕获: " + error);
            error.printStackTrace();
        } finally {
            System.out.println("\n== 结果 ==");
            System.out.println("PASS " + passed + " / FAIL " + failures.size());
            for (String failure : failures) {
                System.out.println(" - " + failure);
            }
            Platform.exit();
            System.exit(failures.isEmpty() ? 0 : 1);
        }
    }

    private static String imageUrlOf(Node node) {
        if (node == null) {
            return null;
        }
        if (node instanceof ImageView view && view.getImage() != null) {
            return view.getImage().getUrl();
        }
        if (node instanceof Label label) {
            Node graphic = label.getGraphic();
            if (graphic != null) {
                String nested = imageUrlOf(graphic);
                if (nested != null) {
                    return nested;
                }
            }
        }
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                String found = imageUrlOf(child);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static void check(String name, Check body) {
        try {
            body.run();
            passed++;
            System.out.println("PASS " + name);
        } catch (Throwable error) {
            Throwable cause = error;
            while (cause.getCause() != null) {
                cause = cause.getCause();
            }
            failures.add(name + " :: " + cause.getMessage());
            System.out.println("FAIL " + name + " — " + cause.getMessage());
        }
    }

    private static void assertThat(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static <T> T fx(FxCallable<T> callable) throws Exception {
        CompletableFuture<T> result = new CompletableFuture<>();
        Platform.runLater(() -> {
            try {
                result.complete(callable.call());
            } catch (Throwable error) {
                result.completeExceptionally(error);
            }
        });
        return result.get(20, TimeUnit.SECONDS);
    }

    private interface Check {
        void run() throws Exception;
    }

    private interface FxCallable<T> {
        T call() throws Exception;
    }
}
