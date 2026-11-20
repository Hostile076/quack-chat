package io.quack.client.media;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import io.quack.common.util.AppDirs;

/**
 * 把视频抽帧/播放放到独立 JVM 里执行。
 *
 * <p>原因：这台机器上的 JavaFX Media（GStreamer 后端）在同一 JVM 里只能成功创建
 * 一个 MediaPlayer，第二个会静默超时。独立进程每次都是“干净”的媒体环境，
 * 缩略图与播放互不影响，也不会卡住主界面。
 */
public final class MediaProcess {

    private MediaProcess() {
    }

    /** windowless=true 时用 javaw，避免弹控制台窗口 */
    public static String javaBinary(boolean windowless) {
        Path bin = Path.of(System.getProperty("java.home"), "bin");
        if (windowless && System.getProperty("os.name", "").toLowerCase().contains("win")) {
            Path javaw = bin.resolve("javaw.exe");
            if (Files.isRegularFile(javaw)) {
                return javaw.toString();
            }
        }
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        return bin.resolve(windows ? "java.exe" : "java").toString();
    }

    public static ProcessBuilder builder(String mainClass, boolean windowless, String... args) {
        List<String> command = new ArrayList<>();
        command.add(javaBinary(windowless));
        command.add("-Dfile.encoding=UTF-8");
        // 显式给子进程可写的临时目录/JavaFX 缓存目录，避免 HTTP 视频报 “Couldn't create backing cache”
        Path tmp = AppDirs.subDir("tmp");
        Path cache = AppDirs.subDir("javafx-cache");
        command.add("-Djava.io.tmpdir=" + tmp.toAbsolutePath());
        command.add("-Djavafx.cachedir=" + cache.toAbsolutePath());
        String dataDir = System.getProperty("quack.data.dir");
        if (dataDir != null && !dataDir.isBlank()) {
            command.add("-Dquack.data.dir=" + dataDir);
        }
        command.add("-cp");
        command.add(normalizeClasspath(System.getProperty("java.class.path")));
        command.add(mainClass);
        command.addAll(List.of(args));
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectErrorStream(true);
        return builder;
    }

    /** 打包后 classpath 可能是相对 app 目录的，子进程工作目录不同会失效，尽量转成绝对路径 */
    private static String normalizeClasspath(String classpath) {
        if (classpath == null || classpath.isBlank()) {
            return classpath == null ? "" : classpath;
        }
        String separator = System.getProperty("path.separator", ";");
        String[] entries = classpath.split(java.util.regex.Pattern.quote(separator));
        List<String> normalized = new ArrayList<>();
        for (String entry : entries) {
            if (entry == null || entry.isBlank()) {
                continue;
            }
            Path path = Path.of(entry);
            if (path.isAbsolute()) {
                normalized.add(entry);
            } else {
                Path absolute = Path.of(System.getProperty("user.dir")).resolve(path).normalize();
                normalized.add(Files.exists(absolute) ? absolute.toString() : entry);
            }
        }
        return String.join(separator, normalized);
    }

    /** 只启动不等待（播放窗口） */
    public static void launch(String mainClass, String... args) throws Exception {
        builder(mainClass, true, args).start();
    }

    /** 启动并限时等待，返回是否正常退出 */
    public static boolean waitFor(Process process, long timeoutSeconds) throws InterruptedException {
        if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            return false;
        }
        return process.exitValue() == 0;
    }
}
