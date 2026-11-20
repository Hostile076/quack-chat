import io.quack.client.media.ImageShrinker;
import io.quack.client.net.ImageCache;
import io.quack.client.net.MediaUrls;
import javafx.application.Platform;
import javafx.scene.image.Image;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * 图片加载优化回归：
 * <ul>
 *   <li>同一 URL + 尺寸只创建一个 Image（内存缓存命中）；</li>
 *   <li>头像上传前会裁成 256×256 的 PNG；</li>
 *   <li>缩略图 URL 参数拼接正确（带签名的 URL 用 &amp;w=）。</li>
 * </ul>
 */
public class ImagePerfProbe {

    private static int passed;
    private static final List<String> failures = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        CompletableFuture<Void> started = new CompletableFuture<>();
        Platform.startup(() -> started.complete(null));
        started.get(20, TimeUnit.SECONDS);

        try {
            check("缩略图参数：无 query 用 ?w=192", () -> {
                String url = MediaUrls.withWidth("http://host:8080/api/files/abc", 192);
                assertThat(url.endsWith("?w=192"), url);
            });
            check("缩略图参数：带签名用 &w=192", () -> {
                String url = MediaUrls.withWidth("http://host:8080/api/files/abc?exp=1&sig=xx", 192);
                assertThat(url.endsWith("&w=192"), url);
            });
            check("缩略图参数：非文件 URL 不动", () -> {
                String url = MediaUrls.withWidth("file:/tmp/a.png", 192);
                assertThat(url.equals("file:/tmp/a.png"), url);
            });

            check("头像上传前裁成 256×256", () -> {
                Path source = Files.createTempFile("perf-avatar-src", ".png");
                BufferedImage image = new BufferedImage(1000, 800, BufferedImage.TYPE_INT_RGB);
                java.awt.Graphics2D graphics = image.createGraphics();
                graphics.setColor(java.awt.Color.ORANGE);
                graphics.fillRect(0, 0, 1000, 800);
                graphics.dispose();
                ImageIO.write(image, "png", source.toFile());

                Path shrunk = ImageShrinker.square(source, 256);
                BufferedImage result = ImageIO.read(shrunk.toFile());
                assertThat(result != null, "缩略图读不出来");
                assertThat(result.getWidth() == 256 && result.getHeight() == 256,
                        "尺寸 = " + result.getWidth() + "x" + result.getHeight());
                assertThat(Files.size(shrunk) < Files.size(source),
                        "缩小后反而更大: " + Files.size(shrunk) + " vs " + Files.size(source));
            });

            check("同一 URL+尺寸复用同一个 Image 实例", () -> {
                Path image = Files.createTempFile("perf-cache", ".png");
                BufferedImage buffered = new BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB);
                ImageIO.write(buffered, "png", image.toFile());
                String url = image.toUri().toString();
                Image first = fx(() -> ImageCache.load(url, 192, 192));
                Image second = fx(() -> ImageCache.load(url, 192, 192));
                assertThat(first != null && first == second, "缓存没有命中");
                Image other = fx(() -> ImageCache.load(url, 96, 96));
                assertThat(other != first, "不同尺寸不应共用同一个 Image");
            });
        } catch (Throwable error) {
            failures.add("未捕获: " + error);
            error.printStackTrace();
        } finally {
            System.out.println("\n== 结果 ==");
            System.out.println("PASS " + passed + " / FAIL " + failures.size());
            for (String failure : failures) {
                System.out.println(" - " + failure);
            }
            Platform.exit();
            System.exit(failures.isEmpty() ? 0 : 1);
        }
    }

    private static void check(String name, Check body) {
        try {
            body.run();
            passed++;
            System.out.println("PASS " + name);
        } catch (Throwable error) {
            Throwable cause = error;
            while (cause.getCause() != null) {
                cause = cause.getCause();
            }
            failures.add(name + " :: " + cause.getMessage());
            System.out.println("FAIL " + name + " — " + cause.getMessage());
        }
    }

    private static void assertThat(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static <T> T fx(FxCallable<T> callable) throws Exception {
        CompletableFuture<T> result = new CompletableFuture<>();
        Platform.runLater(() -> {
            try {
                result.complete(callable.call());
            } catch (Throwable error) {
                result.completeExceptionally(error);
            }
        });
        return result.get(15, TimeUnit.SECONDS);
    }

    private interface Check {
        void run() throws Exception;
    }

    private interface FxCallable<T> {
        T call() throws Exception;
    }
}
