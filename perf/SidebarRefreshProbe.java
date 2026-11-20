package io.quack.client.ui;

import com.sun.net.httpserver.HttpServer;
import io.quack.client.AppContext;
import io.quack.client.store.ClientStore;
import io.quack.common.dto.LoginResp;
import io.quack.common.model.ChatGroup;
import io.quack.common.model.Friend;
import io.quack.common.model.User;
import javafx.application.Platform;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.stage.Stage;

import java.io.File;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 验证：
 * 1) 每个账号的下载目录是分开的
 * 2) 好友资料推送后，会话列表单元格里的头像也会刷新（不只是聊天页头部）
 */
public class SidebarRefreshProbe {

    private static int failures;
    private static int port;

    public static void main(String[] args) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        port = server.getAddress().getPort();
        var image = new java.awt.image.BufferedImage(80, 80, java.awt.image.BufferedImage.TYPE_INT_RGB);
        var g = image.createGraphics();
        g.setColor(new java.awt.Color(40, 160, 90));
        g.fillRect(0, 0, 80, 80);
        g.dispose();
        var bytes = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(image, "png", bytes);
        server.createContext("/a.png", exchange -> {
            exchange.sendResponseHeaders(200, bytes.size());
            exchange.getResponseBody().write(bytes.toByteArray());
            exchange.close();
        });
        server.start();

        CountDownLatch started = new CountDownLatch(1);
        Platform.startup(() -> {
            Platform.setImplicitExit(false);
            started.countDown();
        });
        started.await(10, TimeUnit.SECONDS);

        // ---- 1. 下载目录按账号分开 ----
        MessageCell cell = new MessageCell(() -> "10002", new javafx.beans.property.SimpleDoubleProperty(320),
                message -> new javafx.scene.control.ContextMenu(), url -> {
        }, () -> false);
        File dir = cell.defaultDownloadDir();
        boolean dirOk = dir != null && dir.getAbsolutePath().contains("10002");
        if (!dirOk) {
            failures++;
        }
        System.out.printf("%s 账号 10002 的下载目录 = %s%n", dirOk ? "PASS" : "FAIL", dir);

        // ---- 2. 会话列表头像随推送刷新 ----
        CountDownLatch done = new CountDownLatch(1);
        String[] result = new String[2];
        Platform.runLater(() -> {
            Stage stage = new Stage();
            AppWindow window = new AppWindow(stage, "sidebar-probe", 1000, 700, true);
            AppContext ctx = new AppContext(window);
            ClientStore store = ctx.store();
            List<Friend> friends = new ArrayList<>();
            friends.add(new Friend("10002", "小鲍", "小鲍", true));
            store.reset(new LoginResp(new User(10001L, "alice", "Alice", null), friends,
                    new ArrayList<ChatGroup>()));
            MainView view = new MainView(ctx);
            window.setContent(view, false);
            window.show();

            // 模拟收到 friend.updated（带头像）
            String avatarUrl = "http://127.0.0.1:" + port + "/a.png";
            store.applyUserUpdate(new User(10002L, "bob", "小鲍", avatarUrl));
            view.refreshConversations();

            after(900, () -> {
                // 找到会话列表里那个单元格的头像，看是否已经有图片
                System.out.println("conversation avatar=" + store.conversations().get(0).avatar());
                for (javafx.scene.Node node : window.root().lookupAll(".avatar")) {
                    if (node instanceof Label label) {
                        System.out.println("  avatar node text=" + label.getText()
                                + " graphic=" + (label.getGraphic() == null ? "null" : "yes"));
                    }
                }
                javafx.scene.Node avatarNode = window.root().lookupAll(".avatar").stream()
                        .filter(node -> node instanceof Label label
                                && label.getGraphic() != null)
                        .findFirst().orElse(null);
                result[0] = avatarNode == null ? "none" : "image";

                javafx.scene.Node headerNode = window.root().lookup(".chat-avatar");
                result[1] = headerNode instanceof Label header && header.getGraphic() != null
                        ? "image" : "none";
                stage.close();
                done.countDown();
            });
        });
        done.await(6, TimeUnit.SECONDS);
        boolean avatarOk = "image".equals(result[0]);
        if (!avatarOk) {
            failures++;
        }
        System.out.printf("%s 会话列表头像已随推送刷新（%s）%n", avatarOk ? "PASS" : "FAIL", result[0]);
        boolean headerOk = "image".equals(result[1]);
        if (!headerOk) {
            failures++;
        }
        System.out.printf("%s 聊天头部头像已同步（%s）%n", headerOk ? "PASS" : "FAIL", result[1]);

        System.out.println(failures == 0 ? "SIDEBAR OK" : "SIDEBAR FAILED: " + failures);
        Platform.exit();
        System.exit(failures == 0 ? 0 : 1);
    }

    private static void after(long millis, Runnable action) {
        new javafx.animation.Timeline(new javafx.animation.KeyFrame(
                javafx.util.Duration.millis(millis), e -> action.run())).play();
    }
}
