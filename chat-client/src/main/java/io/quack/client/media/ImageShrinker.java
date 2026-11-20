package io.quack.client.media;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 头像上传前在本地缩小：头像最终只显示到 84px，上传几 MB 的原图既慢又浪费。
 *
 * <p>按中心裁成正方形再缩到 256×256（PNG，保留透明），远程服务器上头像加载会快一个数量级。
 * 读不出来（webp 等 ImageIO 不支持的格式）就原样返回，不影响上传。
 */
public final class ImageShrinker {

    private ImageShrinker() {
    }

    public static Path square(Path source, int maxSize) {
        try {
            BufferedImage image = ImageIO.read(source.toFile());
            if (image == null || image.getWidth() <= 0 || image.getHeight() <= 0) {
                return source;
            }
            int side = Math.min(image.getWidth(), image.getHeight());
            int target = Math.min(maxSize, side);
            int x = (image.getWidth() - side) / 2;
            int y = (image.getHeight() - side) / 2;

            BufferedImage square = new BufferedImage(target, target, BufferedImage.TYPE_INT_ARGB);
            Graphics2D graphics = square.createGraphics();
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING,
                    RenderingHints.VALUE_RENDER_QUALITY);
            graphics.drawImage(image, 0, 0, target, target, x, y, x + side, y + side, null);
            graphics.dispose();

            Path out = Files.createTempFile("quack-avatar-", ".png");
            out.toFile().deleteOnExit();
            ImageIO.write(square, "png", out.toFile());
            return out;
        } catch (IOException | RuntimeException error) {
            return source;
        }
    }
}
