package io.quack.client.media;

import javafx.application.Platform;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * 独立进程入口：抽一帧生成视频封面。
 *
 * <p>用法：{@code ThumbnailMain <video> <resultFile>}；结果写入 resultFile，
 * 一行 {@code OK|封面路径|时长ms|宽|高|real}，避免依赖控制台输出（javaw 无控制台）。
 */
public final class ThumbnailMain {

    public static void main(String[] args) {
        int code = 1;
        String result = "FAIL|no video path";
        try {
            if (args.length == 0) {
                throw new IllegalArgumentException("缺少视频路径");
            }
            Platform.startup(() -> { });
            VideoThumbnailer.Thumb thumb = VideoThumbnailer.captureInProcess(Path.of(args[0]))
                    .get(35, TimeUnit.SECONDS);
            result = "OK|" + thumb.file() + "|" + thumb.durationMs() + "|"
                    + thumb.width() + "|" + thumb.height() + "|" + thumb.realFrame();
            code = 0;
        } catch (Throwable error) {
            result = "FAIL|" + error;
        }
        if (args.length > 1) {
            try {
                Files.writeString(Path.of(args[1]), result);
            } catch (Exception ignored) {
                // 写结果文件失败时仍退出，父进程会用兜底封面
            }
        }
        System.out.println(result);
        System.out.flush();
        Platform.exit();
        Runtime.getRuntime().halt(code);
    }
}
