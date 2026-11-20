package io.quack.client.ui;

import io.quack.client.AppContext;

import java.awt.AWTException;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import javax.imageio.ImageIO;

/**
 * 系统托盘（最小产品化）：显示主窗口 / 退出。
 *
 * <p>用 AWT SystemTray 而不是 JavaFX：JavaFX 没有托盘 API。所有调用都在 FX 线程外，
 * 菜单动作再切回 FX 线程。没有托盘/无头环境时静默跳过。
 */
public final class TraySupport {

    private static TrayIcon icon;

    private TraySupport() {
    }

    public static void install(AppContext ctx) {
        if (!SystemTray.isSupported()) {
            return;
        }
        try {
            BufferedImage image;
            try (InputStream in = TraySupport.class.getResourceAsStream("/icon.png")) {
                image = in == null ? new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB) : ImageIO.read(in);
            }
            PopupMenu menu = new PopupMenu();
            MenuItem show = new MenuItem("显示主窗口");
            show.addActionListener(e -> javafx.application.Platform.runLater(() -> {
                ctx.window().stage().show();
                ctx.window().stage().toFront();
            }));
            MenuItem quit = new MenuItem("退出 Quack Chat");
            quit.addActionListener(e -> javafx.application.Platform.runLater(() -> {
                ctx.shutdown();
                javafx.application.Platform.exit();
                System.exit(0);
            }));
            menu.add(show);
            menu.addSeparator();
            menu.add(quit);

            icon = new TrayIcon(image, "Quack Chat", menu);
            icon.setImageAutoSize(true);
            icon.addActionListener(e -> javafx.application.Platform.runLater(() -> {
                ctx.window().stage().show();
                ctx.window().stage().toFront();
            }));
            SystemTray.getSystemTray().add(icon);
        } catch (AWTException | java.io.IOException error) {
            // 托盘只是锦上添花，失败不影响主程序
            System.err.println("托盘初始化失败: " + error.getMessage());
        }
    }

    public static void remove() {
        if (icon != null) {
            SystemTray.getSystemTray().remove(icon);
            icon = null;
        }
    }
}
