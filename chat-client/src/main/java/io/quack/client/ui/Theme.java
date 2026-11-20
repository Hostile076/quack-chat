package io.quack.client.ui;

import io.quack.client.ui.motion.Motion;
import io.quack.common.util.AppDirs;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Window;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

/**
 * 外观主题：浅色 / 深色 / 跟随系统，以及「减少动态效果」。
 *
 * <p>实现方式是"查找色令牌 + 根节点类名"：{@code app.css} 里所有颜色都引用令牌，
 * 切换主题只需要给场景根节点加上/去掉 {@code dark} 类，JavaFX 会重新解析级联样式，
 * 不需要重建任何界面，也不会打断正在进行的交互。
 *
 * <p>偏好落在 {@code data/appearance.properties}，与账号无关，换号保持一致。
 */
public final class Theme {

    /** 外观模式 */
    public enum Mode {
        LIGHT("浅色"),
        DARK("深色"),
        SYSTEM("跟随系统");

        private final String label;

        Mode(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    private static final Path FILE = AppDirs.dataDir().resolve("appearance.properties");

    private static Mode mode = Mode.SYSTEM;
    private static boolean reduceMotion;
    private static Boolean systemDarkCache;
    private static long systemDarkCheckedAt;
    private static volatile boolean systemProbeStarted;

    static {
        // 第一次引用 Theme 就加载偏好：显式 init() 只是让入口更清晰，这里保证任何入口都不会漏
        load();
        Motion.setReduced(reduceMotion);
    }

    private Theme() {
    }

    /** 启动时调用一次（幂等）：必须早于任何窗口创建 */
    public static void init() {
        load();
        Motion.setReduced(reduceMotion);
        // 启动时同步读一次系统外观（只有一次，约几十毫秒），避免先闪一下浅色再变深色
        if (mode == Mode.SYSTEM && systemDarkCache == null) {
            systemDarkCache = querySystemDark();
            systemDarkCheckedAt = System.currentTimeMillis();
        }
    }

    public static Mode mode() {
        return mode;
    }

    public static void setMode(Mode next) {
        if (next == null || next == mode) {
            return;
        }
        mode = next;
        save();
        refreshAll();
    }

    public static boolean isReduceMotion() {
        return reduceMotion;
    }

    public static void setReduceMotion(boolean value) {
        if (reduceMotion == value) {
            return;
        }
        reduceMotion = value;
        Motion.setReduced(value);
        save();
        refreshAll();
    }

    /** 当前实际是否深色（跟随系统时读注册表） */
    public static boolean isDark() {
        return switch (mode) {
            case DARK -> true;
            case LIGHT -> false;
            case SYSTEM -> systemPrefersDark();
        };
    }

    /** 把当前主题套到一个场景上（所有窗口创建后都要调一次） */
    public static void apply(Scene scene) {
        if (scene == null) {
            return;
        }
        Parent root = scene.getRoot();
        if (root == null) {
            return;
        }
        apply(root);
    }

    public static void apply(Parent root) {
        if (root == null) {
            return;
        }
        boolean dark = isDark();
        if (dark && !root.getStyleClass().contains("dark")) {
            root.getStyleClass().add("dark");
        } else if (!dark) {
            root.getStyleClass().remove("dark");
        }
        if (reduceMotion && !root.getStyleClass().contains("reduce-motion")) {
            root.getStyleClass().add("reduce-motion");
        } else if (!reduceMotion) {
            root.getStyleClass().remove("reduce-motion");
        }
    }

    /** 主题或动效偏好变化后，立即刷新所有已打开窗口 */
    public static void refreshAll() {
        for (Window window : java.util.List.copyOf(Window.getWindows())) {
            if (window.getScene() != null) {
                apply(window.getScene());
            }
        }
    }

    // ------------------------------------------------------------------
    // 跟随系统（Windows：AppsUseLightTheme，0 = 深色）
    // ------------------------------------------------------------------

    /**
     * 读系统外观。**绝不在 FX 线程同步等待进程**：先返回上一次结果（默认浅色），
     * 后台探测完成后再刷新窗口——这样启动和切换窗口都不会因为 reg query 卡顿。
     */
    private static boolean systemPrefersDark() {
        long now = System.currentTimeMillis();
        if (systemDarkCache != null && now - systemDarkCheckedAt < 30_000) {
            return systemDarkCache;
        }
        if (!systemProbeStarted) {
            systemProbeStarted = true;
            java.util.concurrent.CompletableFuture.runAsync(() -> {
                boolean dark = querySystemDark();
                systemDarkCache = dark;
                systemDarkCheckedAt = System.currentTimeMillis();
                systemProbeStarted = false;
                if (mode == Mode.SYSTEM) {
                    javafx.application.Platform.runLater(Theme::refreshAll);
                }
            });
        }
        return systemDarkCache != null && systemDarkCache;
    }

    private static boolean querySystemDark() {
        boolean dark = false;
        Process process = null;
        try {
            process = new ProcessBuilder("reg", "query",
                    "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize",
                    "/v", "AppsUseLightTheme")
                    .redirectErrorStream(true)
                    .start();
            if (!process.waitFor(1200, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
            } else {
                String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                int index = output.indexOf("AppsUseLightTheme");
                if (index >= 0) {
                    String tail = output.substring(index);
                    dark = tail.contains("0x0") || tail.trim().endsWith("0");
                }
            }
        } catch (Exception ignored) {
            // 读不到就按浅色处理，不影响使用
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
        return dark;
    }

    // ------------------------------------------------------------------
    // 持久化
    // ------------------------------------------------------------------

    private static void load() {
        if (!Files.isRegularFile(FILE)) {
            return;
        }
        Properties properties = new Properties();
        try (InputStream in = Files.newInputStream(FILE)) {
            properties.load(in);
        } catch (IOException ignored) {
            return;
        }
        try {
            mode = Mode.valueOf(properties.getProperty("mode", "SYSTEM").trim().toUpperCase());
        } catch (IllegalArgumentException ignored) {
            mode = Mode.SYSTEM;
        }
        reduceMotion = "true".equalsIgnoreCase(properties.getProperty("reduceMotion", "false"));
    }

    private static void save() {
        Properties properties = new Properties();
        properties.setProperty("mode", mode.name());
        properties.setProperty("reduceMotion", String.valueOf(reduceMotion));
        try (OutputStream out = Files.newOutputStream(FILE)) {
            properties.store(out, "Quack Chat appearance");
        } catch (IOException ignored) {
            // 写不了就本次生效
        }
    }
}
