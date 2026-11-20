package io.quack.client.store;

import io.quack.common.util.AppDirs;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 会话级本地偏好：置顶、消息免打扰、隐藏会话。
 *
 * <p>这些是**本机偏好**，只影响这台设备的显示，所以不需要经过服务端，
 * 落到 data/ui-prefs.properties 即可（换设备不同步，符合直觉）。
 */
public class ConversationPrefs {

    private final Path file = AppDirs.dataDir().resolve("ui-prefs.properties");
    private final Map<String, Boolean> pinned = new ConcurrentHashMap<>();
    private final Map<String, Boolean> muted = new ConcurrentHashMap<>();
    private final Map<String, Boolean> hidden = new ConcurrentHashMap<>();
    /** 全局：新消息提示音 */
    private volatile boolean notifySound = true;
    /** 全局：界面字号（px） */
    private volatile int fontSize = 13;
    /** 全局：下载完成后自动打开文件 */
    private volatile boolean autoOpenDownload = false;
    /** 全局：是否显示被“隐藏会话”藏起来的会话（否则隐藏后没入口找回来） */
    private volatile boolean showHiddenSessions = false;

    public ConversationPrefs() {
        load();
    }

    public int getFontSize() {
        return fontSize;
    }

    public void setFontSize(int value) {
        this.fontSize = Math.max(11, Math.min(20, value));
        save();
    }

    public boolean isAutoOpenDownload() {
        return autoOpenDownload;
    }

    public void setAutoOpenDownload(boolean value) {
        this.autoOpenDownload = value;
        save();
    }

    public boolean isShowHiddenSessions() {
        return showHiddenSessions;
    }

    public void setShowHiddenSessions(boolean value) {
        this.showHiddenSessions = value;
        save();
    }

    public boolean isNotifySound() {
        return notifySound;
    }

    public void setNotifySound(boolean value) {
        this.notifySound = value;
        save();
    }

    public boolean isPinned(String key) {
        return Boolean.TRUE.equals(pinned.get(key));
    }

    public void setPinned(String key, boolean value) {
        put(pinned, key, value);
        save();
    }

    public boolean isMuted(String key) {
        return Boolean.TRUE.equals(muted.get(key));
    }

    public void setMuted(String key, boolean value) {
        put(muted, key, value);
        save();
    }

    public boolean isHidden(String key) {
        return Boolean.TRUE.equals(hidden.get(key));
    }

    public void setHidden(String key, boolean value) {
        put(hidden, key, value);
        save();
    }

    private static void put(Map<String, Boolean> map, String key, boolean value) {
        if (value) {
            map.put(key, Boolean.TRUE);
        } else {
            map.remove(key);
        }
    }

    private void load() {
        if (!Files.isRegularFile(file)) {
            return;
        }
        Properties properties = new Properties();
        try (InputStream in = Files.newInputStream(file)) {
            properties.load(in);
        } catch (IOException ignored) {
            return;
        }
        notifySound = !"false".equalsIgnoreCase(properties.getProperty("notifySound", "true"));
        try {
            fontSize = Integer.parseInt(properties.getProperty("fontSize", "13").trim());
        } catch (NumberFormatException ignored) {
            fontSize = 13;
        }
        autoOpenDownload = "true".equalsIgnoreCase(properties.getProperty("autoOpenDownload", "false"));
        showHiddenSessions = "true".equalsIgnoreCase(properties.getProperty("showHiddenSessions", "false"));
        for (String name : properties.stringPropertyNames()) {
            String value = properties.getProperty(name);
            if (name.startsWith("pinned.")) {
                pinned.put(name.substring(7), Boolean.TRUE);
            } else if (name.startsWith("muted.")) {
                muted.put(name.substring(6), Boolean.TRUE);
            } else if (name.startsWith("hidden.")) {
                hidden.put(name.substring(7), Boolean.TRUE);
            }
        }
    }

    private void save() {
        Properties properties = new Properties();
        pinned.keySet().forEach(key -> properties.setProperty("pinned." + key, "true"));
        muted.keySet().forEach(key -> properties.setProperty("muted." + key, "true"));
        hidden.keySet().forEach(key -> properties.setProperty("hidden." + key, "true"));
        properties.setProperty("notifySound", String.valueOf(notifySound));
        properties.setProperty("fontSize", String.valueOf(fontSize));
        properties.setProperty("autoOpenDownload", String.valueOf(autoOpenDownload));
        properties.setProperty("showHiddenSessions", String.valueOf(showHiddenSessions));
        try (OutputStream out = Files.newOutputStream(file)) {
            properties.store(out, "Quack Chat per-conversation preferences");
        } catch (IOException ignored) {
            // 写不了就本次生效
        }
    }
}
