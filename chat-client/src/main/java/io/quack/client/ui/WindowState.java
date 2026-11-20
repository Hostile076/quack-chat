package io.quack.client.ui;

import io.quack.common.util.AppDirs;
import javafx.geometry.Rectangle2D;
import javafx.stage.Screen;
import javafx.stage.Stage;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * 窗口位置/大小的持久化。
 *
 * <p>保存到应用数据目录下的 {@code window.properties}，下次启动还原到上次的位置。
 * 还原前会校验这块区域是否还在某块屏幕内（拔掉外接显示器、改分辨率后不至于跑到屏幕外），
 * 不合法就回退到居中。
 */
public final class WindowState {

    private static final Path FILE = AppDirs.dataDir().resolve("window.properties");

    private WindowState() {
    }

    /**
     * 还原上次的窗口大小/位置。
     *
     * @return 上次是否处于最大化状态
     */
    public static boolean restore(String key, Stage stage, double defaultWidth, double defaultHeight) {
        Properties properties = load();
        double width = number(properties, key + ".width", defaultWidth);
        double height = number(properties, key + ".height", defaultHeight);
        double x = number(properties, key + ".x", Double.NaN);
        double y = number(properties, key + ".y", Double.NaN);

        // 先按屏幕可用区域夹取尺寸：换显示器/改分辨率后，旧的大尺寸不能把窗口撑出屏幕，
        // 否则底部的输入区按钮会被屏幕边缘切掉一半。
        Rectangle2D bounds = boundsFor(x, y);
        width = clamp(width, Math.max(320, stage.getMinWidth()), bounds.getWidth());
        height = clamp(height, Math.max(240, stage.getMinHeight()), bounds.getHeight());
        stage.setWidth(width);
        stage.setHeight(height);

        if (isVisibleOnSomeScreen(x, y, width, height)) {
            // 位置也夹回可视区域内，保证整窗（至少可操作区域）在屏幕里
            stage.setX(clamp(x, bounds.getMinX(), bounds.getMaxX() - width));
            stage.setY(clamp(y, bounds.getMinY(), bounds.getMaxY() - height));
        } else {
            center(stage);
        }
        return Boolean.parseBoolean(properties.getProperty(key + ".maximized", "false"));
    }

    /** 找到保存位置所在的屏幕（找不到就用主屏），并返回其可视区域 */
    private static Rectangle2D boundsFor(double x, double y) {
        if (!Double.isNaN(x) && !Double.isNaN(y)) {
            for (Screen screen : Screen.getScreens()) {
                if (screen.getVisualBounds().contains(x + 1, y + 1)) {
                    return screen.getVisualBounds();
                }
            }
        }
        return Screen.getPrimary().getVisualBounds();
    }

    private static double clamp(double value, double min, double max) {
        if (max < min) {
            return min;
        }
        return Math.max(min, Math.min(max, value));
    }

    public static void save(String key, Stage stage, boolean maximized,
                            double restoreX, double restoreY, double restoreWidth, double restoreHeight) {
        Properties properties = load();
        if (maximized) {
            properties.setProperty(key + ".x", String.valueOf(restoreX));
            properties.setProperty(key + ".y", String.valueOf(restoreY));
            properties.setProperty(key + ".width", String.valueOf(restoreWidth));
            properties.setProperty(key + ".height", String.valueOf(restoreHeight));
            properties.setProperty(key + ".maximized", "true");
        } else {
            properties.setProperty(key + ".x", String.valueOf(stage.getX()));
            properties.setProperty(key + ".y", String.valueOf(stage.getY()));
            properties.setProperty(key + ".width", String.valueOf(stage.getWidth()));
            properties.setProperty(key + ".height", String.valueOf(stage.getHeight()));
            properties.setProperty(key + ".maximized", "false");
        }
        try (OutputStream out = Files.newOutputStream(FILE)) {
            properties.store(out, "Quack Chat window state");
        } catch (IOException ignored) {
            // 写不了就下次重新居中，不影响使用
        }
    }

    public static void center(Stage stage) {
        Rectangle2D bounds = Screen.getPrimary().getVisualBounds();
        stage.setX(bounds.getMinX() + (bounds.getWidth() - stage.getWidth()) / 2);
        stage.setY(bounds.getMinY() + (bounds.getHeight() - stage.getHeight()) / 2);
    }

    private static boolean isVisibleOnSomeScreen(double x, double y, double width, double height) {
        if (Double.isNaN(x) || Double.isNaN(y)) {
            return false;
        }
        // 只要窗口有一个角落在某块屏幕的可视区域内，就认为位置有效
        double probeX = x + Math.min(80, width / 2);
        double probeY = y + Math.min(40, height / 2);
        for (Screen screen : Screen.getScreens()) {
            if (screen.getVisualBounds().contains(probeX, probeY)) {
                return true;
            }
        }
        return false;
    }

    private static double number(Properties properties, String key, double fallback) {
        String raw = properties.getProperty(key);
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return Double.parseDouble(raw.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static Properties load() {
        Properties properties = new Properties();
        if (Files.isRegularFile(FILE)) {
            try (InputStream in = Files.newInputStream(FILE)) {
                properties.load(in);
            } catch (IOException ignored) {
                // 文件损坏就当作没有
            }
        }
        return properties;
    }
}
