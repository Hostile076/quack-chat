import io.quack.client.ui.ImageViewerDialog;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import javafx.stage.Window;

/** 单独验证图片查看器能打开 */
public class ViewerProbe {

    public static void main(String[] args) throws Exception {
        Platform.startup(() -> {
            Stage host = new Stage();
            host.setScene(new Scene(new StackPane(), 300, 200));
            host.setX(200);
            host.setY(200);
            host.show();

            String url = new java.io.File("E:\\dsh_workspace\\quack-chat\\docs\\screenshots\\main.png")
                    .toURI().toString();
            System.out.println("opening viewer for " + url);
            ImageViewerDialog.show(host, url);
            System.out.println("viewer closed");
        });

        // 看门狗：6 秒内没退出就打印窗口状态并强杀
        Thread watchdog = new Thread(() -> {
            try {
                Thread.sleep(6000);
            } catch (InterruptedException ignored) {
                return;
            }
            System.out.println("WATCHDOG: 查看器没有关闭，可能卡住了");
            for (Window window : Window.getWindows()) {
                if (window instanceof Stage stage) {
                    System.out.println("  window=" + stage.getTitle() + " showing=" + stage.isShowing()
                            + " opacity=" + stage.getOpacity());
                }
            }
            Runtime.getRuntime().halt(2);
        });
        watchdog.setDaemon(true);
        watchdog.start();

        // 2 秒后截图并关闭
        Thread.sleep(2000);
        Platform.runLater(() -> {
            for (Window window : Window.getWindows()) {
                if (window instanceof Stage stage && "查看图片".equals(stage.getTitle())) {
                    try {
                        var shot = new java.awt.Robot().createScreenCapture(new java.awt.Rectangle(
                                (int) stage.getX(), (int) stage.getY(),
                                (int) stage.getWidth(), (int) stage.getHeight()));
                        javax.imageio.ImageIO.write(shot, "png",
                                new java.io.File("E:\\dsh_workspace\\viewer.png"));
                        System.out.println("captured " + stage.getWidth() + "x" + stage.getHeight());
                    } catch (Exception ex) {
                        System.out.println("capture failed: " + ex);
                    }
                    stage.close();
                }
            }
        });
        Thread.sleep(1500);
        System.exit(0);
    }
}
