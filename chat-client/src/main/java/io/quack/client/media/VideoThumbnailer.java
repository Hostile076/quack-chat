package io.quack.client.media;

import io.quack.common.util.AppDirs;
import javafx.application.Platform;
import javafx.scene.Group;
import javafx.scene.Scene;
import javafx.scene.image.WritableImage;
import javafx.scene.media.Media;
import javafx.scene.media.MediaPlayer;
import javafx.scene.media.MediaView;
import javafx.util.Duration;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * 视频封面生成：用 JavaFX 解码第一帧并截图（不依赖 ffmpeg）。
 *
 * <p>生成失败（编码不支持/没有媒体组件）时退化为“渐变 + 播放按钮 + 时长”的占位封面，
 * 保证消息/动态总能显示一个可点击的封面。
 */
public final class VideoThumbnailer {

    /** 生成结果：封面文件、时长、原始宽高 */
    public record Thumb(Path file, long durationMs, int width, int height, boolean realFrame) {
    }

    // 离屏抓帧期间必须强引用 player/scene，否则可能被 GC 掉导致永远等不到 ready
    private static MediaPlayer activePlayer;
    private static Scene activeScene;

    private VideoThumbnailer() {
    }

    /**
     * 任意线程可调用：优先在独立 JVM 里抽帧（避免 JavaFX Media 单进程只能创建一个播放器），
     * 子进程不可用时退回当前进程。
     */
    public static CompletableFuture<Thumb> capture(Path video) {
        CompletableFuture<Thumb> result = new CompletableFuture<>();
        Thread worker = new Thread(() -> {
            Path resultFile = null;
            try {
                Thumb best = null;
                for (int attempt = 0; attempt < 2; attempt++) {
                    resultFile = AppDirs.subDir("tmp").resolve(
                            "thumb-result-" + UUID.randomUUID().toString().replace("-", "") + ".txt");
                    Process process = MediaProcess.builder("io.quack.client.media.ThumbnailMain",
                            true, video.toString(), resultFile.toString()).start();
                    MediaProcess.waitFor(process, 50);
                    String output = java.nio.file.Files.isRegularFile(resultFile)
                            ? java.nio.file.Files.readString(resultFile) : "";
                    deleteQuietly(resultFile);
                    resultFile = null;
                    Thumb parsed = parseOutput(output);
                    if (parsed != null && parsed.realFrame()) {
                        best = parsed;
                        break;
                    }
                    if (parsed != null) {
                        best = parsed;
                    }
                }
                if (best == null) {
                    throw new IllegalStateException("缩略图进程没有返回结果");
                }
                result.complete(best);
            } catch (Throwable error) {
                result.complete(placeholder(video, 0, 0, 0));
            } finally {
                if (resultFile != null) {
                    deleteQuietly(resultFile);
                }
            }
        }, "th-quack-thumb");
        worker.setDaemon(true);
        worker.start();
        return result;
    }

    private static Thumb parseOutput(String output) {
        if (output == null) {
            return null;
        }
        for (String line : output.split("\\R")) {
            if (!line.startsWith("OK|")) {
                continue;
            }
            String[] parts = line.split("\\|", -1);
            if (parts.length < 6) {
                continue;
            }
            try {
                Path file = Path.of(parts[1]);
                long duration = Long.parseLong(parts[2]);
                int width = Integer.parseInt(parts[3]);
                int height = Integer.parseInt(parts[4]);
                boolean real = Boolean.parseBoolean(parts[5]);
                return new Thumb(file, duration, width, height, real);
            } catch (RuntimeException ignored) {
                return null;
            }
        }
        return null;
    }

    /** 进程内抽帧（ThumbnailMain 与兜底用） */
    public static CompletableFuture<Thumb> captureInProcess(Path video) {
        CompletableFuture<Thumb> result = new CompletableFuture<>();
        Platform.runLater(() -> startCapture(video, result, 0));
        return result;
    }

    private static void startCapture(Path video, CompletableFuture<Thumb> result, int attempt) {
        try {
            System.err.println("[thumb] start " + video + " attempt=" + attempt);
            Media media = new Media(video.toUri().toString());
            MediaPlayer player = new MediaPlayer(media);
            MediaView view = new MediaView(player);
            view.setPreserveRatio(true);
            view.setFitWidth(480);
            // 放进一个离屏 Scene，snapshot 才有渲染管线
            activePlayer = player;
            activeScene = new Scene(new Group(view), 480, 270);
            result.whenComplete((thumb, error) -> {
                activePlayer = null;
                activeScene = null;
            });

            Runnable retryOrPlaceholder = () -> {
                if (result.isDone()) {
                    return;
                }
                view.setMediaPlayer(null);
                player.stop();
                player.dispose();
                if (attempt == 0) {
                    // JavaFX Media 首次初始化偶发 ERROR_MEDIA_INVALID，延迟重试一次
                    javafx.animation.PauseTransition pause =
                            new javafx.animation.PauseTransition(Duration.millis(400));
                    pause.setOnFinished(e -> startCapture(video, result, 1));
                    pause.play();
                } else {
                    result.complete(placeholder(video, 0, 0, 0));
                }
            };
            media.setOnError(retryOrPlaceholder);
            player.setOnError(retryOrPlaceholder);
            player.setOnReady(() -> {
                System.err.println("[thumb] ready");
                double duration = media.getDuration() == null ? 0 : media.getDuration().toMillis();
                int width = (int) Math.max(1, media.getWidth());
                int height = (int) Math.max(1, media.getHeight());
                player.seek(Duration.millis(Math.min(1000, Math.max(0, duration * 0.1))));
                player.pause();
                // 两帧之后再截，确保 seek 的画面已经渲染
                Platform.runLater(() -> Platform.runLater(() -> {
                    try {
                        WritableImage image = view.snapshot(null, null);
                        Path out = AppDirs.subDir("tmp").resolve(
                                "video-cover-" + UUID.randomUUID().toString().replace("-", "") + ".png");
                        BufferedImage buffered = new BufferedImage(
                                (int) image.getWidth(), (int) image.getHeight(), BufferedImage.TYPE_INT_ARGB);
                        for (int y = 0; y < image.getHeight(); y++) {
                            for (int x = 0; x < image.getWidth(); x++) {
                                buffered.setRGB(x, y, image.getPixelReader().getArgb(x, y));
                            }
                        }
                        ImageIO.write(buffered, "png", out.toFile());
                        result.complete(new Thumb(out, (long) duration, width, height, true));
                    } catch (Throwable error) {
                        result.complete(placeholder(video, (long) duration, width, height));
                    } finally {
                        view.setMediaPlayer(null);
                        player.stop();
                        player.dispose();
                    }
                }));
            });
            player.play();
            player.pause();
        } catch (Throwable error) {
            System.err.println("[thumb] init failed " + error);
            result.complete(placeholder(video, 0, 0, 0));
        }
    }

    /** 占位封面：深色渐变 + 播放三角 + 时长 */
    public static Thumb placeholder(Path video, long durationMs, int width, int height) {
        int w = 480;
        int h = 270;
        BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setPaint(new java.awt.GradientPaint(0, 0, new Color(38, 44, 58),
                    0, h, new Color(18, 22, 32)));
            g.fillRect(0, 0, w, h);
            int cx = w / 2;
            int cy = h / 2;
            g.setColor(new Color(255, 255, 255, 200));
            int r = 34;
            g.fillOval(cx - r, cy - r, r * 2, r * 2);
            g.setColor(new Color(30, 34, 44));
            int[] xs = {cx - 10, cx - 10, cx + 18};
            int[] ys = {cy - 15, cy + 15, cy};
            g.fillPolygon(xs, ys, 3);
            g.setColor(new Color(255, 255, 255, 210));
            g.setFont(new Font("SansSerif", Font.PLAIN, 18));
            String name = video == null ? "视频" : video.getFileName().toString();
            if (name.length() > 28) {
                name = name.substring(0, 27) + "…";
            }
            g.drawString(name, 18, h - 44);
            if (durationMs > 0) {
                g.drawString(formatDuration(durationMs), 18, h - 18);
            }
        } finally {
            g.dispose();
        }
        try {
            Path out = AppDirs.subDir("tmp").resolve(
                    "video-cover-" + UUID.randomUUID().toString().replace("-", "") + ".png");
            ImageIO.write(image, "png", out.toFile());
            return new Thumb(out, durationMs, width, height, false);
        } catch (Exception error) {
            return new Thumb(null, durationMs, width, height, false);
        }
    }

    public static String formatDuration(long ms) {
        long totalSeconds = Math.max(0, ms / 1000);
        long minutes = totalSeconds / 60;
        long seconds = totalSeconds % 60;
        return String.format("%d:%02d", minutes, seconds);
    }

    /** 测试/清理用 */
    public static void deleteQuietly(Path file) {
        if (file == null) {
            return;
        }
        try {
            java.nio.file.Files.deleteIfExists(file);
        } catch (Exception ignored) {
            // 忽略
        }
    }
}
