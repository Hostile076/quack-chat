package io.quack.common.util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 应用数据目录。
 *
 * <p>桌面应用不能假设"用户目录一定可写"（打包成 exe、装在 Program Files、
 * 或者企业环境重定向了配置文件时都可能出问题），所以按优先级找一个可写目录：
 *
 * <ol>
 *   <li>{@code -Dquack.data.dir=...} 显式指定（测试/运维常用）；</li>
 *   <li>可执行文件旁边的 {@code data/}（绿色版、解压即用，推荐）；</li>
 *   <li>{@code ~/.quack-chat}（安装到只读目录时退回到这里）；</li>
 *   <li>系统临时目录（最后的兜底，至少别让应用起不来）。</li>
 * </ol>
 *
 * <p>服务端的 Tomcat 工作区、临时文件，客户端的 JavaFX 缓存都放在这个目录下，
 * 避免往系统目录里乱写。
 */
public final class AppDirs {

    private static final String APP_DIR_NAME = "quack-chat";

    private AppDirs() {
    }

    private static volatile Path cached;

    public static Path dataDir() {
        Path result = cached;
        if (result != null) {
            return result;
        }
        synchronized (AppDirs.class) {
            if (cached == null) {
                cached = resolve();
            }
            return cached;
        }
    }

    public static Path subDir(String name) {
        Path dir = dataDir().resolve(name);
        try {
            Files.createDirectories(dir);
        } catch (IOException ignored) {
            // 调用方自己决定降级策略
        }
        return dir;
    }

    private static Path resolve() {
        String override = System.getProperty("quack.data.dir");
        if (override != null && !override.isBlank()) {
            return ensure(Path.of(override.trim()));
        }

        Path besideExe = besideExecutable();
        if (besideExe != null) {
            Path candidate = besideExe.resolve("data");
            if (isUsable(candidate)) {
                return candidate;
            }
        }

        Path home = Path.of(System.getProperty("user.home", "."), ".quack-chat");
        if (isUsable(home)) {
            return home;
        }

        return ensure(Path.of(System.getProperty("java.io.tmpdir", "."), APP_DIR_NAME));
    }

    /** jpackage 启动器会设置 jpackage.app-path，指向 exe 自身 */
    private static Path besideExecutable() {
        String appPath = System.getProperty("jpackage.app-path");
        if (appPath == null || appPath.isBlank()) {
            return null;
        }
        Path exe = Path.of(appPath);
        return exe.getParent();
    }

    private static boolean isUsable(Path dir) {
        Path probe = ensure(dir);
        try {
            Path test = probe.resolve(".write-test");
            Files.writeString(test, "ok");
            Files.deleteIfExists(test);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static Path ensure(Path dir) {
        try {
            Files.createDirectories(dir);
        } catch (IOException ignored) {
            // 交给调用方在使用时报错
        }
        return dir;
    }
}
