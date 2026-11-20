import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 生成应用图标（app.png / app.ico），不依赖任何设计工具或第三方库。
 *
 * <p>用法：{@code java packaging/IconGen.java packaging/app.ico}
 * 会在同目录下同时写出 app.png 供预览。
 *
 * <p>图像内容：圆角渐变蓝底 + 白色对话气泡 + 三个点，呼应“聊天”这个主题。
 * 想改配色，改下面的 COLOR_FROM / COLOR_TO 即可。
 */
public class IconGen {

    private static final Color COLOR_FROM = new Color(24, 191, 250);
    private static final Color COLOR_TO = new Color(9, 120, 196);
    private static final int SIZE = 256;

    public static void main(String[] args) throws IOException {
        Path icoPath = Paths.get(args.length > 0 ? args[0] : "app.ico");
        Path pngPath = icoPath.resolveSibling("app.png");

        BufferedImage image = render();
        ImageIO.write(image, "png", pngPath.toFile());

        ByteArrayOutputStream png = new ByteArrayOutputStream();
        ImageIO.write(image, "png", png);
        writeIco(icoPath, png.toByteArray());

        System.out.println("icon written: " + icoPath.toAbsolutePath());
        System.out.println("preview png : " + pngPath.toAbsolutePath());
    }

    private static BufferedImage render() {
        BufferedImage image = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);

        // 1. 圆角渐变底
        g.setPaint(new GradientPaint(0, 0, COLOR_FROM, SIZE, SIZE, COLOR_TO));
        g.fill(new RoundRectangle2D.Float(6, 6, SIZE - 12, SIZE - 12, 112, 112));

        // 2. 白色对话气泡
        g.setPaint(Color.WHITE);
        g.fill(new RoundRectangle2D.Float(50, 60, 156, 104, 52, 52));

        // 3. 气泡左下角的小尾巴
        Path2D tail = new Path2D.Float();
        tail.moveTo(74, 150);
        tail.lineTo(74, 202);
        tail.lineTo(118, 150);
        tail.closePath();
        g.fill(tail);

        // 4. 三个圆点
        g.setPaint(new Color(12, 150, 214));
        for (int cx : new int[]{93, 128, 163}) {
            g.fill(new Ellipse2D.Float(cx - 9, 103, 18, 18));
        }

        // 5. 一道细细的白色描边，让图标在深色任务栏上也有边界
        g.setPaint(new Color(255, 255, 255, 40));
        g.setStroke(new BasicStroke(2f));
        g.draw(new RoundRectangle2D.Float(6, 6, SIZE - 12, SIZE - 12, 112, 112));

        g.dispose();
        return image;
    }

    /** 写 Vista+ 的 PNG 型 ICO：6 字节文件头 + 16 字节目录项 + PNG 数据 */
    private static void writeIco(Path path, byte[] png) throws IOException {
        try (OutputStream out = Files.newOutputStream(path)) {
            writeShort(out, 0);          // reserved
            writeShort(out, 1);          // type = icon
            writeShort(out, 1);          // count
            out.write(0);                // width 0 => 256
            out.write(0);                // height 0 => 256
            out.write(0);                // color count
            out.write(0);                // reserved
            writeShort(out, 1);          // planes
            writeShort(out, 32);         // bpp
            writeInt(out, png.length);
            writeInt(out, 22);           // offset
            out.write(png);
        }
    }

    private static void writeShort(OutputStream out, int value) throws IOException {
        out.write(value & 0xFF);
        out.write((value >> 8) & 0xFF);
    }

    private static void writeInt(OutputStream out, int value) throws IOException {
        out.write(value & 0xFF);
        out.write((value >> 8) & 0xFF);
        out.write((value >> 16) & 0xFF);
        out.write((value >> 24) & 0xFF);
    }
}
