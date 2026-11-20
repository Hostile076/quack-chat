import io.quack.client.AppContext;
import io.quack.client.ui.AppWindow;
import io.quack.client.ui.MainView;
import io.quack.common.dto.LoginResp;
import io.quack.common.model.ChatGroup;
import io.quack.common.model.ChatMessage;
import io.quack.common.model.ChatType;
import io.quack.common.model.Friend;
import io.quack.common.model.MsgType;
import io.quack.common.model.User;
import javafx.application.Platform;
import javafx.geometry.Bounds;
import javafx.geometry.Rectangle2D;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.Region;
import javafx.stage.Screen;
import javafx.stage.Stage;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * 布局边界回归：
 * <ol>
 *   <li>输入区拖拽方向与鼠标一致（往上拖变高）；</li>
 *   <li>输入区有上下限，最小时工具栏完整可见（不会半隐藏）；</li>
 *   <li>窗口缩小后输入区自动夹取；</li>
 *   <li>窗口从过大的旧尺寸还原时，不会超出当前屏幕。</li>
 * </ol>
 *
 * <p>用法：{@code java LayoutProbe}
 */
public class LayoutProbe {

    private static int passed;
    private static final List<String> failures = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        CompletableFuture<Void> started = new CompletableFuture<>();
        Platform.startup(() -> started.complete(null));
        started.get(20, TimeUnit.SECONDS);

        Stage stage = fx(Stage::new);
        AppWindow window = fx(() -> new AppWindow(stage, "布局探针", 1000, 700, true));
        AppContext ctx = fx(() -> new AppContext(window));
        fx(() -> {
            seed(ctx);
            window.setContent(new MainView(ctx), false);
            window.show();
            return null;
        });
        Thread.sleep(500);
        layout(window.root());

        try {
            Node composer = find(window.root(), ".composer");
            Node handle = find(window.root(), ".split-handle-v");
            Node messageInput = find(window.root(), ".message-input");
            Node sendButton = find(window.root(), ".send-button");
            Node toolbar = sendButton == null ? null : sendButton.getParent();

            check("输入区 / 分割线 / 工具栏都存在", () -> {
                assertThat(composer != null && handle != null && toolbar != null && messageInput != null,
                        "缺少节点: composer=" + (composer != null) + " handle=" + (handle != null)
                                + " toolbar=" + (toolbar != null) + " input=" + (messageInput != null));
            });

            double initial = fx(() -> ((Region) composer).getHeight());
            check("输入区初始高度合理", () ->
                    assertThat(initial >= 100 && initial < 220, "初始高度 " + initial));

            drag(handle, 0, -60);
            Thread.sleep(150);
            double taller = fx(() -> ((Region) composer).getHeight());
            check("往上拖 → 输入区变高（方向和鼠标一致）", () ->
                    assertThat(taller > initial + 30, "高度 " + initial + " -> " + taller));

            drag(handle, 0, -600);
            Thread.sleep(150);
            double maxed = fx(() -> ((Region) composer).getHeight());
            check("输入区有上限，不会吃掉消息区", () ->
                    assertThat(maxed <= 400, "被拖到 " + maxed));

            drag(handle, 0, 800);
            Thread.sleep(150);
            double min = fx(() -> ((Region) composer).getHeight());
            check("往下拖 → 输入区变矮并停在下限", () ->
                    assertThat(min >= 100 && min <= 140, "最小高度 " + min));

            check("输入区最小时工具栏完整可见", () -> fx(() -> {
                double composerHeight = ((Region) composer).getHeight();
                double toolbarBottom = toolbar.getBoundsInParent().getMaxY();
                assertThat(toolbarBottom <= composerHeight + 0.5,
                        "工具栏底部 " + toolbarBottom + " 超出输入区底部 " + composerHeight);
                assertThat(((Region) messageInput).getHeight() >= 39,
                        "输入框被压到 " + ((Region) messageInput).getHeight());
                return null;
            }));
            shot(window.root(), "docs/screenshots/apple/ui-composer-min.png");

            check("窗口缩到最小后输入区自动夹取", () -> {
                fx(() -> {
                    window.resizeTo(760, 540);
                    return null;
                });
                Thread.sleep(400);
                layout(window.root());
                fx(() -> {
                    Bounds toolbarBounds = toolbar.localToScene(toolbar.getBoundsInLocal());
                    double sceneHeight = window.root().getScene().getHeight();
                    assertThat(toolbarBounds.getMaxY() <= sceneHeight + 0.5,
                            "工具栏超出窗口底部: " + toolbarBounds.getMaxY() + " > " + sceneHeight);
                    double composerHeight = ((Region) composer).getHeight();
                    double chatHeight = ((Region) find(window.root(), ".chat-pane")).getHeight();
                    assertThat(composerHeight <= chatHeight * 0.55 + 1,
                            "输入区 " + composerHeight + " 超过聊天区 55%");
                    return null;
                });
            });

            check("弹窗最小尺寸跟随内容（按钮不会被裁）", () -> {
                Stage owner = fx(Stage::new);
                io.quack.client.ui.ModalShell shell = fx(() ->
                        new io.quack.client.ui.ModalShell(owner, "最小尺寸", 420, 320));
                javafx.scene.layout.VBox content = new javafx.scene.layout.VBox(12);
                content.setMinHeight(520);
                content.setMinWidth(360);
                fx(() -> {
                    shell.setContent(content);
                    shell.showAndWait();
                    shell.stage().setWidth(200);
                    shell.stage().setHeight(200);
                    return null;
                });
                Thread.sleep(200);
                fx(() -> {
                    assertThat(shell.stage().getHeight() >= 520,
                            "弹窗被压到 " + shell.stage().getHeight() + "，内容会被裁");
                    assertThat(shell.stage().getWidth() >= 360,
                            "弹窗宽度被压到 " + shell.stage().getWidth());
                    shell.close();
                    return null;
                });
            });
            check("窗口从过大的旧尺寸还原时不超过屏幕", () -> {
                java.nio.file.Path file = io.quack.common.util.AppDirs.dataDir().resolve("window.properties");
                java.nio.file.Files.createDirectories(file.getParent());
                java.nio.file.Files.writeString(file,
                        "main.width=4000\nmain.height=3000\nmain.x=120\nmain.y=90\nmain.maximized=false\n");
                Stage restoreStage = fx(Stage::new);
                AppWindow restored = fx(() -> new AppWindow(restoreStage, "bounds", 800, 600, true));
                fx(() -> {
                    restored.show();
                    return null;
                });
                Thread.sleep(350);
                Rectangle2D visual = Screen.getPrimary().getVisualBounds();
                fx(() -> {
                    assertThat(restoreStage.getWidth() <= visual.getWidth() + 1,
                            "宽度 " + restoreStage.getWidth() + " > 屏幕 " + visual.getWidth());
                    assertThat(restoreStage.getHeight() <= visual.getHeight() + 1,
                            "高度 " + restoreStage.getHeight() + " > 屏幕 " + visual.getHeight());
                    assertThat(restoreStage.getX() >= visual.getMinX() - 1, "x 在屏幕外: " + restoreStage.getX());
                    assertThat(restoreStage.getY() >= visual.getMinY() - 1, "y 在屏幕外: " + restoreStage.getY());
                    assertThat(restoreStage.getX() + restoreStage.getWidth() <= visual.getMaxX() + 2,
                            "右边界超出屏幕");
                    assertThat(restoreStage.getY() + restoreStage.getHeight() <= visual.getMaxY() + 2,
                            "下边界超出屏幕");
                    restoreStage.close();
                    return null;
                });
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

    // ------------------------------------------------------------------

    private static void check(String name, ThrowingRunnable body) {
        try {
            body.run();
            passed++;
            System.out.println("PASS " + name);
        } catch (Throwable error) {
            Throwable cause = error;
            while (cause.getCause() != null) {
                cause = cause.getCause();
            }
            String message = cause.getMessage() == null ? cause.toString() : cause.getMessage();
            failures.add(name + " :: " + message);
            System.out.println("FAIL " + name + " — " + message);
        }
    }

    private static void assertThat(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    /**
     * 在句柄上按下并拖动。x/y 传 -1，让窗口边缘缩放过滤器认为事件不在窗口内，
     * 避免它 consume 掉合成事件；SplitHandle 只依赖 screen 坐标。
     */
    private static void drag(Node handle, double deltaX, double deltaY) throws Exception {
        fx(() -> {
            double screenX = handle.getScene().getWindow().getX() + 200;
            double screenY = handle.getScene().getWindow().getY() + 200;
            handle.fireEvent(mouse(MouseEvent.MOUSE_PRESSED, screenX, screenY));
            handle.fireEvent(mouse(MouseEvent.MOUSE_DRAGGED, screenX + deltaX, screenY + deltaY));
            handle.fireEvent(mouse(MouseEvent.MOUSE_RELEASED, screenX + deltaX, screenY + deltaY));
            return null;
        });
    }

    private static MouseEvent mouse(javafx.event.EventType<MouseEvent> type, double screenX, double screenY) {
        return new MouseEvent(type, -1, -1, screenX, screenY, MouseButton.PRIMARY, 1,
                false, false, false, false, true, false, false, true, false, false, null);
    }

    private static Node find(Parent root, String selector) {
        return root.lookup(selector);
    }

    private static void layout(Parent root) throws Exception {
        fx(() -> {
            Scene scene = root.getScene();
            root.applyCss();
            if (root instanceof Region region) {
                region.resize(scene.getWidth(), scene.getHeight());
            }
            root.layout();
            return null;
        });
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
        return result.get(15, TimeUnit.SECONDS);
    }

    /** 把当前界面存成 PNG，作为修复后的视觉证据 */
    private static void shot(Parent root, String path) throws Exception {
        fx(() -> {
            root.applyCss();
            root.layout();
            javafx.scene.image.WritableImage image = root.snapshot(null, null);
            int width = (int) Math.round(image.getWidth());
            int height = (int) Math.round(image.getHeight());
            java.awt.image.BufferedImage buffered =
                    new java.awt.image.BufferedImage(width, height, java.awt.image.BufferedImage.TYPE_INT_ARGB);
            javafx.scene.image.PixelReader reader = image.getPixelReader();
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    buffered.setRGB(x, y, reader.getArgb(x, y));
                }
            }
            java.nio.file.Path target = java.nio.file.Path.of(path);
            java.nio.file.Files.createDirectories(target.getParent());
            javax.imageio.ImageIO.write(buffered, "png", target.toFile());
            System.out.println("  shot " + path);
            return null;
        });
    }

    private static void seed(AppContext ctx) {
        User me = new User(10001L, "alice", "Alice", null);
        ctx.store().reset(new LoginResp(me,
                List.of(new Friend("10002", "小鲍", "小鲍", true)),
                List.of(new ChatGroup("q10001", "摸鱼小分队", 3))));
        long now = System.currentTimeMillis();
        var bob = ctx.store().find(ChatType.PRIVATE, "10002");
        if (bob != null) {
            for (int i = 0; i < 6; i++) {
                bob.messages().add(new ChatMessage("m" + i, "c" + i, i % 2 == 0 ? "10002" : "10001",
                        i % 2 == 0 ? "小鲍" : "Alice", "10001", ChatType.PRIVATE, MsgType.TEXT,
                        "第 " + i + " 条测试消息，用来验证消息区高度", now - (6 - i) * 60_000L));
            }
        }
        ctx.store().resort();
    }

    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    private interface FxCallable<T> {
        T call() throws Exception;
    }
}
