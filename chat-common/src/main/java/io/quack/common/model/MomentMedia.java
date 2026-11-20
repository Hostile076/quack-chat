package io.quack.common.model;

/**
 * 空间动态里的一个媒体附件。
 *
 * @param type       IMAGE / VIDEO
 * @param url        原文件 URL（/api/files/...）
 * @param cover      视频封面 URL（图片为自己的 url）
 * @param durationMs 视频时长（图片为 0）
 * @param width      像素宽（未知为 0）
 * @param height     像素高（未知为 0）
 */
public record MomentMedia(String type, String url, String cover,
                          long durationMs, int width, int height) {

    public static MomentMedia image(String url, int width, int height) {
        return new MomentMedia("IMAGE", url, url, 0, width, height);
    }

    public static MomentMedia video(String url, String cover, long durationMs, int width, int height) {
        return new MomentMedia("VIDEO", url, cover, durationMs, width, height);
    }

    public boolean isVideo() {
        return "VIDEO".equalsIgnoreCase(type);
    }
}
