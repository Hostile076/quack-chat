package io.quack.client.net;

/** 文件 URL 解析：服务端返回的是 /api/files/xxx 相对路径，展示/播放前补上 http base。 */
public final class MediaUrls {

    private static volatile String base = "";

    private MediaUrls() {
    }

    public static void setServer(String websocketUrl) {
        base = FileUploader.httpBase(websocketUrl);
    }

    public static String resolve(String url) {
        if (url == null || url.isBlank()) {
            return "";
        }
        if (url.startsWith("http://") || url.startsWith("https://") || url.startsWith("file:")) {
            return url;
        }
        return base + url;
    }

    /**
     * 给服务端图片 URL 加一个宽度参数，让服务端返回缩略图而不是原图。
     * 头像/聊天气泡只需要几百像素，远程服务器上原图可能是几 MB，下载会明显卡顿。
     */
    public static String withWidth(String url, int width) {
        String resolved = resolve(url);
        if (resolved.isEmpty() || !resolved.contains("/api/files/")) {
            return resolved;
        }
        if (resolved.matches(".*[?&]w=\\d+.*")) {
            return resolved;
        }
        return resolved + (resolved.contains("?") ? "&" : "?") + "w=" + width;
    }
}
