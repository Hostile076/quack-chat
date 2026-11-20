package io.quack.client;

import io.quack.common.util.AppDirs;
import javafx.application.Application;

/**
 * 打包成 exe 时使用的引导类。
 *
 * <p>为什么不能直接把 {@link ChatClientApplication} 当作 main-class：
 * JDK 的启动器发现主类继承自 {@code javafx.application.Application} 时，
 * 会强制要求 JavaFX 以“模块”形式存在，否则直接报
 * “缺少 JavaFX 运行时组件”。把入口换成这个普通类，再把 JavaFX 的 jar 放在
 * classpath 上（原生库由 JavaFX 自己解压），就能在 jpackage 的 app-image 里正常启动。
 *
 * <p>同时这里在 JavaFX 初始化之前把临时目录指到可写位置，避免原生库解压失败。
 *
 * <p><b>帧率</b>：JavaFX 默认把动画脉冲锁在 60Hz，拖窗口就会“一顿一顿”。
 * 这里默认把脉冲提到 240Hz（{@code javafx.animation.pulse}），并关闭 Prism 的垂直同步等待。
 * 想要更高可以用 {@code -Dquack.pulse=1000} 或 {@code -Dquack.fullspeed=true}
 * （不封顶，实测 700+ FPS，代价是功耗）；想要省电就 {@code -Dquack.pulse=60}。
 */
public final class Launcher {

    private Launcher() {
    }

    public static void main(String[] args) {
        System.setProperty("java.io.tmpdir", AppDirs.subDir("tmp").toAbsolutePath().toString());

        if ("true".equalsIgnoreCase(System.getProperty("quack.fullspeed", "false"))) {
            System.setProperty("javafx.animation.fullspeed", "true");
        } else {
            System.setProperty("javafx.animation.pulse",
                    System.getProperty("quack.pulse", "240"));
        }
        if ("true".equalsIgnoreCase(System.getProperty("quack.vsync.off", "true"))) {
            System.setProperty("prism.vsync", "false");
        }

        Application.launch(ChatClientApplication.class, args);
    }
}
