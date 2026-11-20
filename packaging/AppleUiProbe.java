import io.quack.client.AppContext;
import io.quack.client.store.Conversation;
import io.quack.common.model.ChatType;
import io.quack.common.model.GroupDetail;
import io.quack.client.ui.AccountDialog;
import io.quack.client.ui.AddFriendDialog;
import io.quack.client.ui.AppWindow;
import io.quack.client.ui.ContactsPane;
import io.quack.client.ui.GeneralSettingsDialog;
import io.quack.client.ui.Icons;
import io.quack.client.ui.MainView;
import io.quack.client.ui.MomentSpaceView;
import io.quack.client.ui.ProfileView;
import io.quack.client.ui.Theme;
import io.quack.client.ui.Toast;
import io.quack.client.ui.motion.Motion;
import io.quack.client.ui.motion.Spring;
import javafx.application.Platform;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.stage.Popup;
import javafx.stage.Stage;
import javafx.stage.Window;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/**
 * 客户端界面级验收：登录 → 主界面 → 发消息 → 打开各类弹窗 →
 * 浅色/深色切换 → 弹簧动效 → 截图。
 *
 * <p>用法：{@code java AppleUiProbe <ws-url> <输出目录>}
 */
public class AppleUiProbe {

    private static int passed;
    private static final List<String> failures = new ArrayList<>();
    private static Path outDir;

    public static void main(String[] args) throws Exception {
        String url = args.length > 0 ? args[0] : "ws://127.0.0.1:18080/ws";
        outDir = Path.of(args.length > 1 ? args[1] : "docs/screenshots/apple");
        Files.createDirectories(outDir);

        CompletableFuture<Void> started = new CompletableFuture<>();
        Platform.startup(() -> started.complete(null));
        started.get(20, TimeUnit.SECONDS);

        Theme.init();
        Stage stage = fx(() -> new Stage());
        AppWindow window = fx(() -> new AppWindow(stage, "Quack Chat", 1080, 700, true));
        AppContext ctx = fx(() -> new AppContext(window));
        fx(() -> {
            window.show();
            return null;
        });

        try {
            // ---------- 登录页：整窗渐变、无外框 ----------
            check("登录页是整窗渐变，没有外层卡片/窗口边框", () -> {
                Stage loginStage = fx(Stage::new);
                AppWindow loginProbe = fx(() -> new AppWindow(loginStage, "登录", 380, 600, false));
                fx(() -> {
                    loginProbe.setTitleBarVisible(false);
                    loginProbe.setContent(new io.quack.client.ui.LoginView(new AppContext(loginProbe)), false);
                    loginProbe.show();
                    return null;
                });
                Thread.sleep(300);
                fx(() -> {
                    Parent loginRoot = loginProbe.root();
                    loginRoot.applyCss();
                    loginRoot.layout();
                    Node card = loginRoot.lookup(".login-card");
                    assertThat(card instanceof Region, "找不到 login-card");
                    javafx.scene.layout.Background cardBackground = ((Region) card).getBackground();
                    javafx.scene.paint.Paint cardFill = cardBackground == null || cardBackground.getFills().isEmpty()
                            ? null : cardBackground.getFills().get(0).getFill();
                    assertThat(!(cardFill instanceof javafx.scene.paint.Color cardColor) || cardColor.getOpacity() == 0,
                            "登录卡仍有不透明背景（外层大框没去掉）");
                    Node frame = loginRoot.lookup(".window-frame");
                    assertThat(frame != null && frame.getStyleClass().contains("login-shell"),
                            "登录窗口没有 login-shell（外框没去掉）");
                    Node field = loginRoot.lookup(".login-field");
                    assertThat(field instanceof Region, "找不到登录输入框");
                    assertThat(loginRoot.lookupAll(".traffic-light").isEmpty(), "左上角圆点应该已经移除");
                    // 只看 LoginView 内部的按钮：窗口隐藏的标题栏里也有一套同名按钮
                    Parent loginView = (Parent) loginRoot.lookup(".login-root");
                    assertThat(loginView != null, "找不到 login-root");
                    java.util.Set<Node> chromeButtons = loginView.lookupAll(".title-button");
                    assertThat(chromeButtons.size() == 3, "右上角窗口按钮数量 = " + chromeButtons.size());
                    // 必须和主界面标题栏按钮完全同尺寸
                    window.root().applyCss();
                    window.root().layout();
                    Button mainMinimize = (Button) window.root().lookup(".title-button");
                    Button loginMinimize = (Button) loginView.lookup(".title-button");
                    assertThat(mainMinimize != null && loginMinimize != null, "缺少窗口按钮");
                    assertThat(Math.abs(mainMinimize.prefWidth(-1) - loginMinimize.prefWidth(-1)) <= 1,
                            "按钮宽度不一致: 主界面 " + mainMinimize.prefWidth(-1)
                                    + " / 登录页 " + loginMinimize.prefWidth(-1));
                    assertThat(Math.abs(mainMinimize.prefHeight(-1) - loginMinimize.prefHeight(-1)) <= 1,
                            "按钮高度不一致: 主界面 " + mainMinimize.prefHeight(-1)
                                    + " / 登录页 " + loginMinimize.prefHeight(-1));
                    // 登录页不预填演示账号/密码，也不再显示演示账号提示
                    java.util.Set<Node> loginFields = loginView.lookupAll(".login-field");
                    assertThat(loginFields.size() >= 2, "登录输入框数量 = " + loginFields.size());
                    for (Node node : loginFields) {
                        if (node instanceof javafx.scene.control.TextInputControl input) {
                            assertThat(input.getText().isEmpty(), "登录页仍预填了内容: " + input.getText());
                        }
                    }
                    for (Node node : loginView.lookupAll(".login-hint")) {
                        if (node instanceof javafx.scene.control.Label label && label.isVisible()
                                && label.getText() != null && label.getText().contains("演示账号")) {
                            throw new AssertionError("演示账号提示行还在: " + label.getText());
                        }
                    }                    javafx.scene.layout.Background fieldBackground = ((Region) field).getBackground();
                    javafx.scene.paint.Paint fieldFill = fieldBackground == null || fieldBackground.getFills().isEmpty()
                            ? null : fieldBackground.getFills().get(0).getFill();
                    assertThat(fieldFill instanceof javafx.scene.paint.Color fieldColor && fieldColor.getOpacity() > 0.2,
                            "输入框没有半透明填充，渐变背景上看不清");
                    // 点右上角关闭按钮应该真的能关闭窗口
                    Button closeButton = (Button) loginView.lookup(".title-button-close");
                    assertThat(closeButton != null, "缺少关闭按钮");
                    closeButton.fire();
                    return null;
                });
                Thread.sleep(300);
                fx(() -> {
                    assertThat(!loginStage.isShowing(), "点击关闭按钮没有关闭窗口");
                    return null;
                });
            });
            // ---------- 登录 ----------
            long loginStart = System.currentTimeMillis();
            ctx.login(url, "演示账号1", "123456").get(20, TimeUnit.SECONDS);
            waitUntil(() -> ctx.store().me() != null, 10_000);
            check("登录后进入主界面", () -> {
                assertThat(window.stage().getTitle().contains("10001"),
                        "窗口标题未包含 QC 号：" + window.stage().getTitle());
                Parent content = (Parent) window.root().lookup(".main-root");
                assertThat(content instanceof MainView, "主界面不是 MainView");
                assertThat(!ctx.store().conversations().isEmpty(), "会话列表为空");
            });
            System.out.println("  登录耗时 " + (System.currentTimeMillis() - loginStart) + "ms");

            // ---------- Toast 自动消失 ----------
            check("Toast 2 秒后自动消失", () -> {
                String text = "测试Toast-" + System.nanoTime();
                fx(() -> {
                    Toast.showAboveWindow(window.stage(), text, true);
                    return null;
                });
                Thread.sleep(300);
                boolean showing = fx(() -> Window.getWindows().stream().anyMatch(w ->
                        w instanceof Popup popup && popup.isShowing()
                                && popup.getContent().stream().anyMatch(node ->
                                node instanceof Label label && text.equals(label.getText()))));
                assertThat(showing, "Toast 没有显示");
                Thread.sleep(2400);
                boolean stillShowing = fx(() -> Window.getWindows().stream().anyMatch(w ->
                        w instanceof Popup popup && popup.isShowing()
                                && popup.getContent().stream().anyMatch(node ->
                                node instanceof Label label && text.equals(label.getText()))));
                assertThat(!stillShowing, "Toast 2 秒后仍然挂在屏幕上");
            });
            // ---------- 通讯录：创建群聊入口 + 建群 API ----------
            check("通讯录上方有「创建群聊」入口", () -> {
                boolean found = fx(() -> {
                    ContactsPane pane = new ContactsPane(ctx, conversation -> {
                    }, () -> {
                    });
                    try {
                        return containsText(pane, "创建群聊");
                    } finally {
                        pane.dispose();
                    }
                });
                assertThat(found, "通讯录里找不到创建群聊入口");
            });

            check("点击「创建群聊」会打开选好友弹窗", () -> {
                java.util.concurrent.atomic.AtomicBoolean opened =
                        new java.util.concurrent.atomic.AtomicBoolean(false);
                java.util.concurrent.atomic.AtomicInteger rowCount =
                        new java.util.concurrent.atomic.AtomicInteger(0);
                fx(() -> {
                    ContactsPane pane = new ContactsPane(ctx, conversation -> {
                    }, () -> {
                    });
                    HBox entry = findRowWithText(pane, "创建群聊");
                    assertThat(entry != null, "找不到创建群聊入口行");
                    javafx.animation.PauseTransition closer =
                            new javafx.animation.PauseTransition(javafx.util.Duration.millis(700));
                    closer.setOnFinished(event -> {
                        for (Window w : Window.getWindows()) {
                            if (w instanceof Stage s && "创建群聊".equals(s.getTitle())) {
                                opened.set(true);
                                if (s.getScene() != null) {
                                    rowCount.accumulateAndGet(
                                            s.getScene().getRoot().lookupAll(".option-row").size(),
                                            Math::max);
                                }
                                s.hide();
                            }
                        }
                    });
                    closer.play();
                    entry.fireEvent(new javafx.scene.input.MouseEvent(
                            javafx.scene.input.MouseEvent.MOUSE_CLICKED, 0, 0, 0, 0,
                            javafx.scene.input.MouseButton.PRIMARY, 1,
                            false, false, false, false, true, false, false, true, false, false, null));
                    pane.dispose();
                    return null;
                });
                Thread.sleep(1400);
                assertThat(opened.get(), "创建群聊弹窗没有打开");
                assertThat(rowCount.get() > 0, "创建群聊弹窗里没有可选好友");
            });
            check("弹窗里选好友并创建群聊", () -> {
                String name = "弹窗群-" + System.currentTimeMillis();
                fx(() -> {
                    ContactsPane pane = new ContactsPane(ctx, conversation -> {
                    }, () -> {
                    });
                    HBox entry = findRowWithText(pane, "创建群聊");
                    assertThat(entry != null, "找不到创建群聊入口行");
                    entry.fireEvent(new javafx.scene.input.MouseEvent(
                            javafx.scene.input.MouseEvent.MOUSE_CLICKED, 0, 0, 0, 0,
                            javafx.scene.input.MouseButton.PRIMARY, 1,
                            false, false, false, false, true, false, false, true, false, false, null));
                    Stage dialog = findStage("创建群聊");
                    assertThat(dialog != null && dialog.getScene() != null, "弹窗没有打开");
                    javafx.scene.control.TextField nameField =
                            (javafx.scene.control.TextField) dialog.getScene().lookup(".login-field");
                    assertThat(nameField != null, "弹窗里没有群名输入框");
                    nameField.setText(name);
                    javafx.scene.control.CheckBox firstFriend =
                            (javafx.scene.control.CheckBox) dialog.getScene().lookup(".check-box");
                    assertThat(firstFriend != null, "弹窗里没有好友勾选框");
                    firstFriend.setSelected(true);
                    Button confirm = null;
                    for (Node node : dialog.getScene().getRoot().lookupAll(".button")) {
                        if (node instanceof Button button && "创建群聊".equals(button.getText())) {
                            confirm = button;
                            break;
                        }
                    }
                    assertThat(confirm != null, "弹窗里没有创建按钮");
                    confirm.fire();
                    pane.dispose();
                    return null;
                });
                waitUntil(() -> ctx.store().conversations().stream()
                        .anyMatch(c -> c.getChatType() == ChatType.GROUP
                                && name.equals(c.getTitle())), 8_000);
                Conversation created = ctx.store().conversations().stream()
                        .filter(c -> c.getChatType() == ChatType.GROUP && name.equals(c.getTitle()))
                        .findFirst().orElse(null);
                assertThat(created != null, "弹窗创建后本地没有群会话");
            });
            check("创建群聊 API 会新建会话", () -> {
                String name = "UI探针群-" + System.currentTimeMillis();
                GroupDetail detail = ctx.createGroup(name, java.util.List.of("10002"))
                        .get(15, TimeUnit.SECONDS);
                assertThat(detail != null && detail.id() != null, "创建群聊没有返回群资料");
                assertThat(detail.ownerId().equals("10001"), "群主不是自己: " + detail.ownerId());
                assertThat(detail.members().stream().anyMatch(m -> m.userId().equals("10002")),
                        "群成员缺少被拉的好友");
                waitUntil(() -> ctx.store().find(ChatType.GROUP, detail.id()) != null, 5_000);
                Conversation created = ctx.store().find(ChatType.GROUP, detail.id());
                assertThat(created != null && created.getTitle().equals(name),
                        "本地没有新建群会话");
            });
            // ---------- 顶部提示：错误也要 2 秒后自动淡出 ----------
            check("顶部错误提示 2 秒后自动淡出", () -> {
                io.quack.client.ui.MainView mainView =
                        (io.quack.client.ui.MainView) window.root().lookup(".main-root");
                assertThat(mainView != null, "找不到主界面");
                fx(() -> {
                    mainView.showBanner("测试错误提示", true);
                    return null;
                });
                Thread.sleep(300);
                fx(() -> {
                    Node banner = window.root().lookup(".banner");
                    assertThat(banner != null && banner.isVisible(), "提示没有显示");
                    assertThat(banner.getStyleClass().contains("banner-error"), "错误提示样式不对");
                    return null;
                });
                Thread.sleep(2200);
                fx(() -> {
                    Node banner = window.root().lookup(".banner");
                    assertThat(banner.getOpacity() < 1.0 || !banner.isVisible(), "2 秒后没有开始淡出");
                    return null;
                });
                Thread.sleep(700);
                fx(() -> {
                    Node banner = window.root().lookup(".banner");
                    assertThat(!banner.isVisible(), "淡出后提示仍然可见");
                    return null;
                });
            });
            // ---------- 发消息（真实服务端） ----------
            String probeText = "UI探针消息-" + System.currentTimeMillis();
            fx(() -> {
                Conversation target = ctx.store().conversations().stream()
                        .filter(c -> c.getChatType() == io.quack.common.model.ChatType.PRIVATE)
                        .findFirst().orElse(ctx.store().conversations().get(0));
                // 和 ChatPane 一样先乐观插入本地消息，ACK 才会找到 clientMsgId
                String clientMsgId = java.util.UUID.randomUUID().toString();
                io.quack.common.model.User me = ctx.store().me();
                target.messages().add(new io.quack.common.model.ChatMessage(
                        Conversation.LOCAL_ID_PREFIX + clientMsgId,
                        clientMsgId,
                        me.id(),
                        me.nickname(),
                        target.getPeerId(),
                        target.getChatType(),
                        io.quack.common.model.MsgType.TEXT,
                        probeText,
                        System.currentTimeMillis()));
                ctx.sendMessage(target, clientMsgId, probeText);
                return null;
            });
            waitUntil(() -> ctx.store().conversations().stream()
                    .flatMap(c -> c.messages().stream())
                    .anyMatch(m -> probeText.equals(m.content()) && !m.id().startsWith("local-")), 10_000);
            check("消息发送并收到服务端 ACK", () -> {
            });

            // ---------- 附件：真实上传 + 图片气泡渲染 ----------
            java.nio.file.Path png = Files.createTempFile("quack-probe", ".png");
            java.awt.image.BufferedImage image = new java.awt.image.BufferedImage(240, 140,
                    java.awt.image.BufferedImage.TYPE_INT_RGB);
            java.awt.Graphics2D graphics = image.createGraphics();
            graphics.setColor(new java.awt.Color(10, 122, 255));
            graphics.fillRect(0, 0, 240, 140);
            graphics.setColor(java.awt.Color.WHITE);
            graphics.setFont(new java.awt.Font("SansSerif", java.awt.Font.BOLD, 22));
            graphics.drawString("Quack", 68, 80);
            graphics.dispose();
            ImageIO.write(image, "png", new File(png.toString()));
            fx(() -> {
                Conversation target = ctx.store().conversations().stream()
                        .filter(c -> c.getChatType() == io.quack.common.model.ChatType.PRIVATE)
                        .findFirst().orElse(ctx.store().conversations().get(0));
                ctx.sendAttachment(target, png, true);
                return null;
            });
            waitUntil(() -> ctx.store().conversations().stream()
                    .flatMap(c -> c.messages().stream())
                    .anyMatch(m -> m.msgType() == io.quack.common.model.MsgType.IMAGE
                            && !m.id().startsWith("local-")), 20_000);
            Thread.sleep(800);
            check("图片消息上传并渲染", () -> {
            });

            // ---------- @ 候选 / 右键菜单的独立 Popup 主题 ----------
            check("右键菜单带上主题样式", () -> {
                javafx.scene.control.ContextMenu menu = fx(() -> {
                    javafx.scene.control.ContextMenu created = new javafx.scene.control.ContextMenu();
                    io.quack.client.ui.Popups.install(created);
                    created.getItems().add(new javafx.scene.control.MenuItem("查看资料"));
                    created.show(window.root(), 200, 200);
                    return created;
                });
                Thread.sleep(500);
                try {
                    assertThat(menu.getScene() != null, "菜单没有场景");
                    assertThat(menu.getScene().getStylesheets().stream().anyMatch(s -> s.endsWith("app.css")),
                            "菜单场景没有 app.css");
                    assertThat(menu.getScene().getRoot().getStyleClass().contains("root"),
                            "菜单根节点没有 token 类");
                } finally {
                    fx(() -> {
                        menu.hide();
                        return null;
                    });
                }
            });

            // ---------- 主界面截图（浅色 / 深色） ----------
            Thread.sleep(600);
            shot(window.root(), "ui-main-light.png");
            fx(() -> {
                Theme.setMode(Theme.Mode.DARK);
                return null;
            });
            waitUntil(Theme::isDark, 3000);
            Thread.sleep(300);
            shot(window.root(), "ui-main-dark.png");
            check("深色主题应用到主窗口根节点", () ->
                    assertThat(window.root().getStyleClass().contains("dark"), "主窗口没有 dark 类"));

            // ---------- 通用设置：外观切换（真实点击） ----------
            openDialog(() -> fx(() -> {
                GeneralSettingsDialog.show(stage, ctx);
                return null;
            }), 1200);
            Stage settings = newestWindow(stage);
            check("通用设置弹窗打开", () -> assertThat(settings != null, "没有新窗口"));
            Thread.sleep(500);
            shot(settings.getScene().getRoot(), "ui-settings-dark.png");

            check("外观分段控件有三个选项", () -> {
                Set<Node> segments = settings.getScene().getRoot().lookupAll(".segment");
                assertThat(segments.size() == 3, "分段数量 = " + segments.size());
            });
            fx(() -> {
                for (Node node : settings.getScene().getRoot().lookupAll(".segment")) {
                    if (node instanceof Button button && "浅色".equals(button.getText())) {
                        button.fire();
                    }
                }
                return null;
            });
            waitUntil(() -> !Theme.isDark(), 3000);
            Thread.sleep(400);
            check("点击「浅色」立即切换主题", () ->
                    assertThat(!settings.getScene().getRoot().getStyleClass().contains("dark"),
                            "设置窗仍是深色"));
            shot(settings.getScene().getRoot(), "ui-settings-light.png");

            check("「减少动态效果」开关存在", () -> {
                Set<Node> tracks = settings.getScene().getRoot().lookupAll(".switch-track");
                assertThat(tracks.size() >= 3, "开关数量 = " + tracks.size());
            });
            closeStage(settings);

            // ---------- 其它弹窗 ----------
            openDialog(() -> fx(() -> {
                AccountDialog.show(stage, ctx, () -> {
                }, null);
                return null;
            }), 1000);
            Stage account = newestWindow(stage);
            check("设置入口弹窗打开", () -> assertThat(account != null, "设置弹窗没打开"));
            Thread.sleep(400);
            shot(account.getScene().getRoot(), "ui-account.png");
            closeStage(account);

            openDialog(() -> fx(() -> {
                AddFriendDialog.show(stage, ctx);
                return null;
            }), 1200);
            Stage addFriend = newestWindow(stage);
            check("加好友弹窗打开", () -> assertThat(addFriend != null, "加好友弹窗没打开"));
            Thread.sleep(400);
            shot(addFriend.getScene().getRoot(), "ui-addfriend.png");
            closeStage(addFriend);

            openDialog(() -> fx(() -> {
                ProfileView.show(stage, ctx, "10002", text -> {
                });
                return null;
            }), 1600);
            Stage profile = newestWindow(stage);
            check("个人主页弹窗打开", () -> assertThat(profile != null, "个人主页没打开"));
            Thread.sleep(700);
            shot(profile.getScene().getRoot(), "ui-profile.png");
            closeStage(profile);

            openDialog(() -> fx(() -> {
                MomentSpaceView.showTimeline(stage, ctx);
                return null;
            }), 1600);
            Stage space = newestWindow(stage);
            check("QC 空间窗口打开", () -> assertThat(space != null, "空间窗口没打开"));
            Thread.sleep(800);
            shot(space.getScene().getRoot(), "ui-space.png");
            closeStage(space);

            // ---------- 弹簧引擎 ----------
            check("弹簧从当前值收敛到目标", () -> {
                DoubleProperty property = new SimpleDoubleProperty(0);
                Platform.runLater(() -> Spring.to(property, 1, 2.5, 0.3, 0.8));
                waitUntil(() -> Math.abs(property.get() - 1) < 0.01 && Spring.activeCount() == 0, 4000);
            });
            check("弹簧可中途改目标（不跳变）", () -> {
                DoubleProperty property = new SimpleDoubleProperty(0);
                Platform.runLater(() -> Spring.to(property, 1, 0, 0.4, 1.0));
                Thread.sleep(120);
                double mid = property.get();
                Platform.runLater(() -> Spring.to(property, 0, 0, 0.4, 1.0));
                Thread.sleep(80);
                double after = property.get();
                assertThat(Math.abs(after - mid) < 0.25, "改目标后跳变了：" + mid + " -> " + after);
                waitUntil(() -> Math.abs(property.get()) < 0.01 && Spring.activeCount() == 0, 4000);
            });
            check("减少动态效果 = 立即到位", () -> {
                Motion.setReduced(true);
                StackPane node = new StackPane();
                Platform.runLater(() -> Motion.materialize(node, 8));
                waitUntil(() -> node.getOpacity() == 1 && node.getScaleX() == 1, 1000);
                Motion.setReduced(false);
            });
            check("图标集渲染为矢量（非 emoji）", () -> {
                Node icon = fx(() -> Icons.of("chat", 18));
                assertThat(icon.lookup(".glyph") != null, "图标没有 .glyph 路径");
            });
            check("快速来回切换内容不会错层", () -> {
                StackPane host = fx(StackPane::new);
                Region first = fx(() -> {
                    Region region = new Region();
                    region.setId("first");
                    return region;
                });
                Region second = fx(() -> {
                    Region region = new Region();
                    region.setId("second");
                    return region;
                });
                fx(() -> {
                    host.getChildren().add(first);
                    Motion.swap(host, second);
                    Motion.swap(host, first);
                    return null;
                });
                Thread.sleep(500);
                fx(() -> {
                    assertThat(host.getChildren().size() == 1, "切换后子节点数 = " + host.getChildren().size());
                    assertThat(host.getChildren().get(0) == first, "切换后留下的不是最新内容");
                    assertThat(first.getOpacity() == 1, "最新内容不可见");
                    return null;
                });
            });
            check("Toast 浮层能正常显示", () -> {
                fx(() -> {
                    io.quack.client.ui.Toast.showAboveWindow(stage, "测试提示", true);
                    return null;
                });
                Thread.sleep(700);
            });

            // ---------- 收尾：带附件的会话 + 暗色主界面 ----------
            shot(window.root(), "ui-main-attachment.png");
            check("主题偏好持久化到磁盘", () -> {
                fx(() -> {
                    Theme.setMode(Theme.Mode.DARK);
                    return null;
                });
                Theme.init();
                assertThat(Theme.mode() == Theme.Mode.DARK, "外观模式未持久化");
            });
            fx(() -> {
                Theme.setMode(Theme.Mode.DARK);
                return null;
            });
            Thread.sleep(400);
            shot(window.root(), "ui-main-dark.png");
            fx(() -> {
                Theme.setMode(Theme.Mode.LIGHT);
                return null;
            });

        } catch (Throwable error) {
            failures.add("未捕获异常: " + error);
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
            failures.add(name + " :: " + error.getMessage());
            System.out.println("FAIL " + name + " — " + error.getMessage());
        }
    }

    private static Stage findStage(String title) {
        for (Window window : Window.getWindows()) {
            if (window instanceof Stage stage && title.equals(stage.getTitle())) {
                return stage;
            }
        }
        return null;
    }
    /** 找到包含指定文案的入口行（创建群聊等点击行） */
    private static HBox findRowWithText(Node node, String text) {
        if (node instanceof HBox row && row.getStyleClass().contains("contact-row")
                && containsText(row, text)) {
            return row;
        }
        if (node instanceof javafx.scene.control.ScrollPane scroll && scroll.getContent() != null) {
            HBox found = findRowWithText(scroll.getContent(), text);
            if (found != null) {
                return found;
            }
        }
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                HBox found = findRowWithText(child, text);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }
    /** 递归判断某个节点树里有没有指定文案的 Label */
    private static boolean containsText(Node node, String text) {
        if (node instanceof Label label && text.equals(label.getText())) {
            return true;
        }
        if (node instanceof javafx.scene.control.ScrollPane scroll
                && scroll.getContent() != null && containsText(scroll.getContent(), text)) {
            return true;
        }
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                if (containsText(child, text)) {
                    return true;
                }
            }
        }
        return false;
    }
    private static void assertThat(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static void openDialog(ThrowingRunnable opener, long waitMillis) throws Exception {
        opener.run();
        Thread.sleep(waitMillis);
    }

    private static Stage newestWindow(Stage owner) {
        Stage found = null;
        for (Window window : Window.getWindows()) {
            if (window instanceof Stage candidate && candidate != owner && candidate.isShowing()) {
                if (found == null || candidate.getScene() != null) {
                    found = candidate;
                }
            }
        }
        return found;
    }

    private static void closeStage(Stage stage) throws Exception {
        if (stage != null) {
            fx(() -> {
                stage.close();
                return null;
            });
            Thread.sleep(250);
        }
    }

    private static void waitUntil(BooleanSupplier condition, long timeoutMillis) throws Exception {
        long end = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < end) {
            CompletableFuture<Boolean> result = new CompletableFuture<>();
            Platform.runLater(() -> {
                try {
                    result.complete(condition.getAsBoolean());
                } catch (Throwable error) {
                    result.complete(false);
                }
            });
            if (Boolean.TRUE.equals(result.get(2, TimeUnit.SECONDS))) {
                return;
            }
            Thread.sleep(60);
        }
        throw new AssertionError("等待超时（" + timeoutMillis + "ms）");
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

    private static void shot(Parent root, String name) throws Exception {
        CompletableFuture<Void> done = new CompletableFuture<>();
        Platform.runLater(() -> {
            try {
                root.applyCss();
                Scene scene = root.getScene();
                if (root instanceof Region region && scene != null) {
                    region.resize(scene.getWidth(), scene.getHeight());
                }
                root.layout();
                WritableImage image = root.snapshot(null, null);
                write(image, outDir.resolve(name));
                done.complete(null);
            } catch (Throwable error) {
                done.completeExceptionally(error);
            }
        });
        done.get(20, TimeUnit.SECONDS);
        System.out.println("  shot " + name);
    }

    private static void write(WritableImage image, Path path) throws Exception {
        int width = (int) Math.round(image.getWidth());
        int height = (int) Math.round(image.getHeight());
        BufferedImage buffered = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        PixelReader reader = image.getPixelReader();
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                buffered.setRGB(x, y, reader.getArgb(x, y));
            }
        }
        ImageIO.write(buffered, "png", new File(path.toString()));
    }

    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    private interface FxCallable<T> {
        T call() throws Exception;
    }
}
