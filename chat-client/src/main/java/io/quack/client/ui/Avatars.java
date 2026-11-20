package io.quack.client.ui;

import io.quack.client.net.ImageCache;
import io.quack.client.net.MediaUrls;
import javafx.geometry.Rectangle2D;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.StackPane;
import javafx.scene.shape.Circle;

/**
 * 头像：有上传过头像就显示图片（圆形 + 居中裁切），没有就用 id 哈希挑一个渐变色 + 首字母。
 *
 * <p>两个坑都在这修：
 * <ul>
 *   <li>样式表里的 {@code .avatar} 写死了 44px，会盖掉代码里设的尺寸（84/64/40…），
 *       导致头像大小不一、图片溢出压住文字；这里用<b>内联样式</b>强制回控件真实尺寸。</li>
 *   <li>圆形头像必须按正方形铺满裁切（等价 CSS object-fit: cover），否则会被拉扁或留白。</li>
 * </ul>
 *
 * <p>图片统一走 {@link ImageCache} + 服务端缩略图（{@code ?w=192}）：
 * 远程服务器上原图可能是几 MB，头像只需要 192px，且刷新列表时不应该重复下载。
 */
public final class Avatars {

    private static final String[] GRADIENTS = {
            "linear-gradient(to bottom right, #4f8cff, #7ab8ff)",
            "linear-gradient(to bottom right, #12b7f5, #5ad1f0)",
            "linear-gradient(to bottom right, #7c6cf6, #a99bff)",
            "linear-gradient(to bottom right, #ff8a5c, #ffb37a)",
            "linear-gradient(to bottom right, #2fbf8f, #6fd9b6)",
            "linear-gradient(to bottom right, #f56c9c, #ff9dbb)",
            "linear-gradient(to bottom right, #3d5afe, #7b8cff)",
            "linear-gradient(to bottom right, #0ea5a5, #56d3c8)"
    };

    private Avatars() {
    }

    public static void apply(Label label, String key) {
        apply(label, key, null);
    }

    /** @param avatarUrl 上传的头像地址，为空则用渐变占位 */
    public static void apply(Label label, String key, String avatarUrl) {
        double size = resolveSize(label);
        String sizeStyle = "-fx-min-width:" + size + ";-fx-pref-width:" + size
                + ";-fx-max-width:" + size + ";-fx-min-height:" + size
                + ";-fx-pref-height:" + size + ";-fx-max-height:" + size + ";";

        Image image = avatarUrl == null || avatarUrl.isBlank()
                ? null : ImageCache.load(MediaUrls.withWidth(avatarUrl, 192), 192, 192);
        if (image == null) {
            placeholder(label, key, sizeStyle);
            return;
        }

        ImageView view = new ImageView(image);
        view.setPreserveRatio(true);
        view.setSmooth(true);
        view.setFitWidth(size);
        view.setFitHeight(size);
        view.setClip(new Circle(size / 2, size / 2, size / 2));

        Runnable cover = () -> {
            double width = image.getWidth();
            double height = image.getHeight();
            if (width <= 0 || height <= 0) {
                return;
            }
            double side = Math.min(width, height);
            view.setViewport(new Rectangle2D((width - side) / 2, (height - side) / 2, side, side));
        };
        if (image.getProgress() >= 1) {
            cover.run();
        } else {
            image.progressProperty().addListener((obs, old, progress) -> {
                if (progress.doubleValue() >= 1) {
                    cover.run();
                }
            });
        }

        StackPane holder = new StackPane(view);
        holder.setMinSize(size, size);
        holder.setPrefSize(size, size);
        holder.setMaxSize(size, size);

        label.setText("");
        label.setGraphic(holder);
        // 图片已经裁成圆形，背后不能再留方形底色/边框（否则就是一圈难看的方框）
        label.setStyle("-fx-background-color: transparent;-fx-border-color: transparent;"
                + "-fx-effect: null;" + sizeStyle);
    }

    /** 没有头像时用首字母 + 渐变占位 */
    private static void placeholder(Label label, String key, String sizeStyle) {
        label.setGraphic(null);
        label.setText(initial(label.getText()));
        String gradient = GRADIENTS[Math.floorMod(key == null ? 0 : key.hashCode(), GRADIENTS.length)];
        // 内联样式优先级高于样式表，才能保住调用方指定的尺寸
        label.setStyle("-fx-background-color: " + gradient + ";" + sizeStyle);
    }

    /** 头像控件的真实尺寸：优先布局宽度，其次 pref/min，最后默认 44 */
    private static double resolveSize(Label label) {
        double size = label.getWidth();
        if (size <= 0) {
            size = label.getPrefWidth();
        }
        if (size <= 0) {
            size = label.getMinWidth();
        }
        if (size <= 0 || size == Double.MAX_VALUE) {
            size = 44;
        }
        return Math.round(size);
    }

    /** 头像上的文字：一般取首字；「演示账号12」这类"前缀 + 1~2 位数字"取数字，避免同一批账号全是同一个字 */
    public static String initial(String text) {
        if (text == null || text.isBlank()) {
            return "?";
        }
        String trimmed = text.trim();
        java.util.regex.Matcher matcher = TRAILING_NUMBER.matcher(trimmed);
        if (matcher.matches()) {
            return matcher.group(1);
        }
        return trimmed.substring(0, 1).toUpperCase();
    }

    private static final java.util.regex.Pattern TRAILING_NUMBER =
            java.util.regex.Pattern.compile("^.*\\D(\\d{1,2})$");
}
