import io.quack.client.AppContext;
import io.quack.client.store.Conversation;
import io.quack.client.ui.AppWindow;
import io.quack.client.ui.MainView;
import io.quack.common.dto.UpdateProfileReq;
import io.quack.common.model.ChatMessage;
import io.quack.common.model.ChatType;
import io.quack.common.model.MsgType;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.image.ImageView;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 头像同步回归：A 换头像后，B 的聊天主界面（会话列表 / 聊天头部）必须跟着变，
 * 同时 A 自己的左下角头像也要变。
 *
 * <p>用两个真实登录的客户端验证，覆盖：
 * <ol>
 *   <li>个人头像：朋友侧的会话头像 → 聊天头部头像 / 列表头像；</li>
 *   <li>本人侧：左下角头像立即刷新；</li>
 *   <li>群头像：群成员侧的会话列表 / 群聊头部跟着换。</li>
 * </ol>
 */
public class AvatarSyncProbe {

    private static int passed;
    private static final List<String> failures = new ArrayList<>();
    private static String serverUrl;
    private static String avatarUrl;
    private static String groupAvatarUrl;

    public static void main(String[] args) throws Exception {
        serverUrl = args.length > 0 ? args[0] : "ws://127.0.0.1:18080/ws";
        CompletableFuture<Void> started = new CompletableFuture<>();
        Platform.startup(() -> started.complete(null));
        started.get(20, TimeUnit.SECONDS);

        AtomicReference<AppWindow> windowARef = new AtomicReference<>();
        AtomicReference<AppWindow> windowBRef = new AtomicReference<>();
        AtomicReference<AppContext> ctxARef = new AtomicReference<>();
        AtomicReference<AppContext> ctxBRef = new AtomicReference<>();

        fx(() -> {
            Stage stageA = new Stage();
            AppWindow windowA = new AppWindow(stageA, "A", 900, 640, true);
            AppContext ctxA = new AppContext(windowA);
            windowA.show();
            windowA.stage().setX(40);
            windowA.stage().setY(40);
            windowARef.set(windowA);
            ctxARef.set(ctxA);

            Stage stageB = new Stage();
            AppWindow windowB = new AppWindow(stageB, "B", 900, 640, true);
            AppContext ctxB = new AppContext(windowB);
            windowB.show();
            windowB.stage().setX(980);
            windowB.stage().setY(40);
            windowBRef.set(windowB);
            ctxBRef.set(ctxB);
            return null;
        });

        AppContext ctxA = ctxARef.get();
        AppContext ctxB = ctxBRef.get();
        AppWindow windowA = windowARef.get();
        AppWindow windowB = windowBRef.get();

        try {
            ctxA.login(serverUrl, "演示账号1", "123456").get(20, TimeUnit.SECONDS);
            ctxB.login(serverUrl, "演示账号2", "123456").get(20, TimeUnit.SECONDS);
            Thread.sleep(800);
            fx(() -> {
                windowA.root().applyCss();
                windowA.root().layout();
                windowB.root().applyCss();
                windowB.root().layout();
                return null;
            });

            // B 打开与 A 的私聊
            check("B 打开与 A 的私聊", () -> {
                fx(() -> {
                    selectConversation(windowB.root(), ctxB, ChatType.PRIVATE, "10001");
                    return null;
                });
                Thread.sleep(300);
            });

            // A 先给 B 发一条消息，后面检查"历史消息里的发送者头像"是否也会更新
            check("A 给 B 发一条测试消息", () -> {
                fx(() -> {
                    Conversation conversation = ctxA.store().find(ChatType.PRIVATE, "10002");
                    assertThat(conversation != null, "A 找不到与 B 的会话");
                    String clientMsgId = java.util.UUID.randomUUID().toString();
                    conversation.messages().add(new ChatMessage(
                            Conversation.LOCAL_ID_PREFIX + clientMsgId, clientMsgId,
                            "10001", "演示账号1", "10002", ChatType.PRIVATE, MsgType.TEXT,
                            "头像同步测试", System.currentTimeMillis()));
                    ctxA.sendMessage(conversation, clientMsgId, "头像同步测试");
                    return null;
                });
                Thread.sleep(900);
            });
            // A 上传并保存新头像
            check("A 上传新头像", () -> {
                avatarUrl = uploadAvatar(ctxA);
                assertThatNotBlank(avatarUrl, "上传头像失败");
                ctxA.updateProfile(new UpdateProfileReq(
                        "演示账号1", null, avatarUrl, "新头像测试", "男", "", "", "")).get(20, TimeUnit.SECONDS);
                Thread.sleep(1200);
            });

            check("A 自己的左下角头像立即更新", () -> {
                Node rail = find(windowA.root(), ".rail-avatar");
                fx(() -> {
                    System.out.println("DBG me.avatar=" + (ctxA.store().me() == null ? "null" : ctxA.store().me().avatar()));
                    System.out.println("DBG rail class=" + (rail == null ? "null" : rail.getClass().getSimpleName()));
                    if (rail instanceof Label label) {
                        System.out.println("DBG rail text=" + label.getText() + " graphic=" + label.getGraphic());
                    }
                    System.out.println("DBG mainAvatarProp=" + ctxA.store().myAvatarProperty().get());
                    return null;
                });
                String url = imageUrlOf(rail);
                assertThatNotBlank(url, "A 的左下角头像没有图片");
                assertMentions(url, avatarUrl, "A 左下角头像");
            });

            check("B 的会话列表里 A 的头像同步更新", () -> {
                Conversation conversation = fx(() -> ctxB.store().find(ChatType.PRIVATE, "10001"));
                assertThat(conversation != null, "B 找不到与 A 的会话");
                fx(() -> {
                    assertMentions(conversation.avatar(), avatarUrl, "B 会话数据里的头像");
                    return null;
                });
                // 遍历列表单元格，确认至少有一个头像是新 URL
                boolean found = fx(() -> {
                    for (Node cell : windowB.root().lookupAll(".list-cell")) {
                        String url = imageUrlOf(cell);
                        if (url != null && url.equals(avatarUrl)) {
                            return true;
                        }
                    }
                    return false;
                });
                assertThat(found, "会话列表里没有出现新头像");
            });

            check("B 的聊天头部头像同步更新", () -> {
                Node headerAvatar = find(windowB.root(), ".chat-avatar");
                assertThat(headerAvatar != null, "找不到聊天头部头像");
                String url = imageUrlOf(headerAvatar);
                assertThatNotBlank(url, "聊天头部头像没有图片");
                assertMentions(url, avatarUrl, "B 聊天头部头像");
            });

            check("B 聊天记录里的发送者头像同步更新", () -> {
                boolean found = fx(() -> {
                    for (Node cell : windowB.root().lookupAll(".list-cell")) {
                        if (textOf(cell).contains("头像同步测试")) {
                            String url = imageUrlOf(cell);
                            if (url != null && url.equals(avatarUrl)) {
                                return true;
                            }
                        }
                    }
                    return false;
                });
                assertThat(found, "历史消息的气泡头像没有更新");
            });
            // 群头像：A 是 q10001 群主
            check("A 上传并保存群头像", () -> {
                groupAvatarUrl = uploadAvatar(ctxA);
                ctxA.updateGroup("q10001", null, null, groupAvatarUrl).get(20, TimeUnit.SECONDS);
                Thread.sleep(1200);
            });

            check("B 的群会话头像同步更新", () -> {
                Conversation group = fx(() -> ctxB.store().find(ChatType.GROUP, "q10001"));
                assertThat(group != null, "B 找不到群会话");
                fx(() -> {
                    assertMentions(group.avatar(), groupAvatarUrl, "B 群会话头像");
                    return null;
                });
            });

            check("B 打开群聊后头部头像正确", () -> {
                fx(() -> {
                    selectConversation(windowB.root(), ctxB, ChatType.GROUP, "q10001");
                    return null;
                });
                Thread.sleep(400);
                String url = imageUrlOf(find(windowB.root(), ".chat-avatar"));
                assertThatNotBlank(url, "群聊头部头像没有图片");
                assertMentions(url, groupAvatarUrl, "B 群聊头部头像");
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

    private static String uploadAvatar(AppContext ctx) throws Exception {
        Path png = Files.createTempFile("avatar-probe", ".png");
        BufferedImage image = new BufferedImage(96, 96, BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D graphics = image.createGraphics();
        graphics.setColor(new java.awt.Color(30 + (int) (Math.random() * 200), 90, 200));
        graphics.fillRect(0, 0, 96, 96);
        graphics.setColor(java.awt.Color.WHITE);
        graphics.setFont(new java.awt.Font("SansSerif", java.awt.Font.BOLD, 26));
        graphics.drawString("Q", 34, 62);
        graphics.dispose();
        javax.imageio.ImageIO.write(image, "png", png.toFile());
        return ctx.uploadFile(png).get(20, TimeUnit.SECONDS).url();
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void selectConversation(Parent root, AppContext ctx, ChatType type, String peerId) {
        Conversation target = ctx.store().find(type, peerId);
        if (target == null) {
            return;
        }
        ListView list = (ListView) root.lookup(".conversation-list");
        if (list == null) {
            return;
        }
        list.getSelectionModel().select(target);
    }

    /** 从任意节点里找 ImageView 的图片 URL（头像渲染成 Label > StackPane > ImageView） */
    private static String textOf(Node node) {
        StringBuilder builder = new StringBuilder();
        if (node instanceof Label label && label.getText() != null) {
            builder.append(label.getText()).append(' ');
        }
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                builder.append(textOf(child));
            }
        }
        return builder.toString();
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
            if (graphic instanceof StackPane) {
                String nested = imageUrlOf(graphic);
                if (nested != null) {
                    return nested;
                }
            }
            return imageUrlOf(graphic);
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

    private static Node find(Parent root, String selector) {
        return root.lookup(selector);
    }

    private static void assertMentions(String actual, String expected, String label) {
        if (actual == null || !actual.contains(expected)) {
            throw new AssertionError(label + " 还是旧的: " + actual + "（期望包含 " + expected + "）");
        }
    }

    private static void assertThatNotBlank(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new AssertionError(message);
        }
    }

    private static void assertThat(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
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

    private static <T> T fx(FxCallable<T> callable) throws Exception {
        CompletableFuture<T> result = new CompletableFuture<>();
        Platform.runLater(() -> {
            try {
                result.complete(callable.call());
            } catch (Throwable error) {
                result.completeExceptionally(error);
            }
        });
        return result.get(25, TimeUnit.SECONDS);
    }

    private interface Check {
        void run() throws Exception;
    }

    private interface FxCallable<T> {
        T call() throws Exception;
    }
}
