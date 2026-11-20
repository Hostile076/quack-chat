package io.quack.client.media;

import java.nio.file.Path;
import java.util.function.Consumer;

/**
 * 视频播放交给系统：先下载到本地缓存，再用系统默认播放器打开。
 *
 * <p>应用内只保留视频封面（缩略图），不再内置播放器窗口；
 * HEVC/H.264 都由系统解码器负责，HEVC 是否可播取决于系统是否安装了对应解码器。
 */
public final class SystemVideo {

    private SystemVideo() {
    }

    public static void open(String url, Consumer<String> status) {
        Thread worker = new Thread(() -> {
            try {
                status.accept("正在下载视频…");
                Path file = VideoCache.local(url);
                status.accept("正在用系统播放器打开…");
                if (java.awt.Desktop.isDesktopSupported()) {
                    java.awt.Desktop.getDesktop().open(file.toFile());
                    status.accept("视频已交给系统播放器");
                } else {
                    status.accept("当前系统不支持自动打开，文件位置：" + file);
                }
            } catch (Exception error) {
                status.accept("视频打开失败：" + error.getMessage());
            }
        }, "th-system-video");
        worker.setDaemon(true);
        worker.start();
    }
}
