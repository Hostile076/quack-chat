package io.quack.client;

import io.quack.client.ui.AppWindow;
import io.quack.client.ui.LoginView;
import io.quack.common.util.AppDirs;
import javafx.application.Application;
import javafx.stage.Stage;

/**
 * JavaFX 客户端入口。
 *
 * <p>窗口本身是无边框的（{@link AppWindow} 自绘标题栏与缩放），
 * 生命周期：登录界面 -&gt; 主界面，两个界面共用同一个窗口外壳，只换内容。
 */
public class ChatClientApplication extends Application {

    private AppWindow window;
    private AppContext context;

    /**
     * 必须在 {@link #launch} 之前改 java.io.tmpdir：
     * JavaFX 会把平台相关的原生库解压到临时目录，打包成 exe 后系统临时目录不一定可写。
     */
    public static void main(String[] args) {
        System.setProperty("java.io.tmpdir", AppDirs.subDir("tmp").toAbsolutePath().toString());
        launch(args);
    }

    @Override
    public void start(Stage stage) {
        // 外观偏好要在任何窗口创建之前读入（浅色 / 深色 / 跟随系统 + 减少动态效果）
        io.quack.client.ui.Theme.init();
        window = new AppWindow(stage, "Quack Chat", 380, 600, false);
        // 登录页只要卡片本身：隐藏自绘标题栏
        window.setTitleBarVisible(false);
        context = new AppContext(window);
        window.setContent(new LoginView(context, null, context.accounts().lastServerUrl()));
        window.show();
        // show() 会恢复上次窗口尺寸，必须之后再把登录页尺寸/最小尺寸定下来
        window.setMinSize(340, 570);
        window.resizeTo(380, 600);
        stage.centerOnScreen();
        io.quack.client.ui.TraySupport.install(context);
    }

    @Override
    public void stop() {
        io.quack.client.ui.TraySupport.remove();
        if (context != null) {
            context.shutdown();
        }
        if (window != null) {
            window.persistState();
        }
    }
}
