package io.quack.client.net;

import javafx.scene.image.Image;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 图片内存缓存（LRU）。
 *
 * <p>之前每次刷新列表/重开窗口都会 {@code new Image(url)}，JavaFX 不会复用，等于重新下载一遍。
 * 本地服务器感觉不到，部署到远程后头像会一张张慢慢出来。这里按「URL + 目标尺寸」缓存，
 * 同一张头像/缩略图只下载一次，后续直接复用同一个 {@link Image} 对象（可被多个 ImageView 共享）。
 */
public final class ImageCache {

    /** 最多缓存多少张（都是缩略图，几百张内存可控） */
    private static final int MAX_ENTRIES = 320;

    private static final Map<String, Image> CACHE = new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Image> eldest) {
            return size() > MAX_ENTRIES;
        }
    };

    private ImageCache() {
    }

    /**
     * 加载（或复用）一张按尺寸解码的图片。失败返回 null，调用方退回占位图。
     *
     * @param url 完整 URL（用 {@link MediaUrls#withWidth} 加过缩略图参数更好）
     */
    public static Image load(String url, double width, double height) {
        if (url == null || url.isBlank()) {
            return null;
        }
        String key = url + "@" + Math.round(width) + "x" + Math.round(height);
        synchronized (CACHE) {
            Image cached = CACHE.get(key);
            if (cached != null) {
                return cached;
            }
        }
        Image image;
        try {
            image = new Image(url, width, height, true, true, true);
        } catch (IllegalArgumentException error) {
            return null;
        }
        synchronized (CACHE) {
            CACHE.put(key, image);
        }
        image.errorProperty().addListener((obs, old, failed) -> {
            if (Boolean.TRUE.equals(failed)) {
                synchronized (CACHE) {
                    CACHE.remove(key);
                }
            }
        });
        return image;
    }

    /** 头像统一按 192px 解码：列表 44、聊天头部 38、资料页 84 都够用，且只缓存一份 */
    public static Image avatar(String url) {
        return load(MediaUrls.withWidth(url, 192), 192, 192);
    }

    public static void clear() {
        synchronized (CACHE) {
            CACHE.clear();
        }
    }
}
