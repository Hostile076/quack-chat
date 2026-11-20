package io.quack.client.media;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/** 粗略识别 MP4 视频编码：用于把 HEVC 交给系统播放器（JavaFX Media 一般不支持的编码）。 */
public final class VideoCodecs {

    private VideoCodecs() {
    }

    public static String detect(Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buffer = new byte[1024 * 1024];
            String carry = "";
            int read;
            while ((read = in.read(buffer)) > 0) {
                String chunk = carry + new String(buffer, 0, read, java.nio.charset.StandardCharsets.ISO_8859_1);
                if (chunk.contains("hvc1") || chunk.contains("hev1") || chunk.contains("hvcC")) {
                    return "HEVC";
                }
                if (chunk.contains("avc1") || chunk.contains("avc3") || chunk.contains("avcC")) {
                    return "H264";
                }
                carry = chunk.length() > 8 ? chunk.substring(chunk.length() - 8) : chunk;
            }
        } catch (Exception ignored) {
            // 识别失败就按未知处理
        }
        return "UNKNOWN";
    }

    /** 交给 Windows 默认播放器（HEVC 是否可播取决于系统是否安装 HEVC 解码器） */
    public static boolean openWithSystem(Path file) {
        try {
            if (java.awt.Desktop.isDesktopSupported()) {
                java.awt.Desktop.getDesktop().open(file.toFile());
                return true;
            }
        } catch (Exception error) {
            System.err.println("系统播放器打开失败：" + error);
        }
        return false;
    }
}
