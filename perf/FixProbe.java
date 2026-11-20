package io.quack.client.ui;

import com.sun.net.httpserver.HttpServer;
import io.quack.client.AppContext;
import io.quack.client.store.ClientStore;
import io.quack.common.dto.LoginResp;
import io.quack.common.model.ChatGroup;
import io.quack.common.model.Friend;
import io.quack.common.model.User;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.image.ImageView;
import javafx.scene.layout.StackPane;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.io.File;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 本轮 5 个 bug 的回归测试：
 * 1) 头像按控件尺寸铺满（圆形裁切、不变形）
 * 2) 文件下载真的写盘
 * 3) 资料窗口非模态（主窗口不被锁）
 * 4) 通用设置能打开
 */
public class FixProbe {

    private static int failures;
    private static int port;

    public static void main(String[] args) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        port = server.getAddress().getPort();

        byte[] payload = new byte[120_000];
        for (int i = 0; i < payload.length; i++) {
            payload[i] = (byte) (i % 251);
        }
        server.createContext("/file.bin", exchange -> {
            exchange.sendResponseHeaders(200, payload.length);
            exchange.getResponseBody().write(payload);
            exchange.close();
        });

        java.awt.image.BufferedImage image = new java.awt.image.BufferedImage(120, 60,
                java.awt.image.BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        graphics.setColor(java.awt.Color.ORANGE);
        graphics.fillRect(0, 0, 120, 60);
        graphics.dispose();
        var imageBytes = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(image, "png", imageBytes);
        server.createContext("/avatar.png", exchange -> {
            exchange.sendResponseHeaders(200, imageBytes.size());
            exchange.getResponseBody().write(imageBytes.toByteArray());
            exchange.close();
        });
        server.start();

        // 注意：等待必须在非 FX 线程做，否则会阻塞 JavaFX 线程导致回调永远不执行
        CountDownLatch started = new CountDownLatch(1);
        Platform.startup(() -> {
            Platform.setImplicitExit(false);
            started.countDown();
        });
        started.await(10, TimeUnit.SECONDS);

        try {
            avatarTest();
            downloadTest();
            modalityTest();
            settingsTest();
        } catch (Throwable error) {
            failures++;
            System.out.println("FAIL 探针异常: " + error);
            error.printStackTrace();
        }
        System.out.println(failures == 0 ? "FIX OK" : "FIX FAILED: " + failures);
        Platform.exit();
        System.exit(failures == 0 ? 0 : 1);
    }

    // ------------------------------------------------------------------

    private static void avatarTest() throws Exception {
        CountDownLatch loaded = new CountDownLatch(1);
        String[] graphicInfo = new String[2];

        Platform.runLater(() -> {
            try {
            Label label = new Label("B");
            label.getStyleClass().add("avatar");
            label.setMinSize(64, 64);
            label.setPrefSize(64, 64);
            label.setMaxSize(64, 64);
            StackPane host = new StackPane(label);
            Stage stage = new Stage();
            stage.setScene(new Scene(host, 200, 200));
            stage.show();

            Avatars.apply(label, "10001", "http://127.0.0.1:" + port + "/avatar.png");
            after(900, () -> {
                System.out.println("debug graphic=" + label.getGraphic());
                if (label.getGraphic() instanceof StackPane holder
                        && !holder.getChildren().isEmpty()
                        && holder.getChildren().get(0) instanceof ImageView view) {
                    var viewport = view.getViewport();
                    graphicInfo[0] = String.format("%.0fx%.0f", view.getFitWidth(), view.getFitHeight());
                    graphicInfo[1] = viewport == null ? "null"
                            : String.format("%.0fx%.0f", viewport.getWidth(), viewport.getHeight());
                }
                stage.close();
                loaded.countDown();
            });
            } catch (Throwable error) {
                System.out.println("avatar error: " + error);
                error.printStackTrace();
                loaded.countDown();
            }
        });

        loaded.await(6, TimeUnit.SECONDS);
        boolean ok = "64x64".equals(graphicInfo[0]) && "60x60".equals(graphicInfo[1]);
        if (!ok) {
            failures++;
        }
        System.out.printf("%s 头像尺寸=控件尺寸(%s)，裁切视口为正方形(%s)%n",
                ok ? "PASS" : "FAIL", graphicInfo[0], graphicInfo[1]);
    }

    // ------------------------------------------------------------------

    private static void downloadTest() throws Exception {
        File target = File.createTempFile("quack-download", ".bin");
        target.deleteOnExit();

        MessageCell cell = new MessageCell(() -> "10001", new javafx.beans.property.SimpleDoubleProperty(320),
                message -> new javafx.scene.control.ContextMenu(), url -> {
        }, () -> false);

        cell.downloadTo("m1", "http://127.0.0.1:" + port + "/file.bin", "file.bin", 120_000, target);

        long deadline = System.currentTimeMillis() + 8000;
        while (System.currentTimeMillis() < deadline && target.length() < 120_000) {
            Thread.sleep(100);
        }
        boolean ok = target.length() == 120_000;
        if (!ok) {
            failures++;
        }
        System.out.printf("%s 下载写入文件 %d / 120000 字节%n", ok ? "PASS" : "FAIL", target.length());
    }

    // ------------------------------------------------------------------

    private static void modalityTest() throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        String[] modality = new String[1];

        Platform.runLater(() -> {
            Stage stage = new Stage();
            AppWindow window = new AppWindow(stage, "fix-probe", 900, 650, true);
            AppContext ctx = new AppContext(window);
            window.show();

            ProfileView.show(stage, ctx, "10001", null);
            after(700, () -> {
                for (Window window2 : new ArrayList<>(Window.getWindows())) {
                    if (window2 instanceof Stage dialog
                            && "资料".equals(dialog.getTitle()) && dialog.isShowing()) {
                        modality[0] = String.valueOf(dialog.getModality());
                        dialog.close();
                    }
                }
                window.stage().close();
                done.countDown();
            });
        });

        done.await(6, TimeUnit.SECONDS);
        boolean ok = modality[0] != null && modality[0].equals(String.valueOf(Modality.NONE));
        if (!ok) {
            failures++;
        }
        System.out.printf("%s 资料窗口 modality=%s（应为 NONE，不锁主窗口）%n",
                ok ? "PASS" : "FAIL", modality[0]);
    }

    // ------------------------------------------------------------------

    private static void settingsTest() throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        boolean[] opened = {false};

        Platform.runLater(() -> {
            Stage stage = new Stage();
            AppWindow window = new AppWindow(stage, "fix-probe-2", 900, 650, true);
            AppContext ctx = new AppContext(window);
            window.show();

            GeneralSettingsDialog.show(stage, ctx);
            after(700, () -> {
                for (Window window2 : new ArrayList<>(Window.getWindows())) {
                    if (window2 instanceof Stage dialog
                            && "通用设置".equals(dialog.getTitle()) && dialog.isShowing()) {
                        opened[0] = true;
                        dialog.close();
                    }
                }
                window.stage().close();
                done.countDown();
            });
        });

        done.await(6, TimeUnit.SECONDS);
        if (!opened[0]) {
            failures++;
        }
        System.out.println((opened[0] ? "PASS" : "FAIL") + " 通用设置窗口能打开");
    }

    private static void after(long millis, Runnable action) {
        new javafx.animation.Timeline(new javafx.animation.KeyFrame(
                javafx.util.Duration.millis(millis), e -> action.run())).play();
    }
}
