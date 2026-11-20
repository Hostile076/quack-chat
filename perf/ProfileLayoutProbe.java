import io.quack.client.AppContext;
import io.quack.client.ui.AppWindow;
import io.quack.client.ui.ProfileView;
import io.quack.common.model.UserProfile;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

import java.io.File;

/** 渲染资料页，检查头像尺寸与文字是否重叠 */
public class ProfileLayoutProbe {

    public static void main(String[] args) throws Exception {
        // 造一张 400x160 的测试图（宽扁，最容易暴露裁切问题）
        var image = new java.awt.image.BufferedImage(400, 160,
                java.awt.image.BufferedImage.TYPE_INT_RGB);
        var g = image.createGraphics();
        g.setColor(new java.awt.Color(60, 120, 220));
        g.fillRect(0, 0, 400, 160);
        g.setColor(java.awt.Color.WHITE);
        g.setFont(new java.awt.Font("SansSerif", java.awt.Font.BOLD, 48));
        g.drawString("AVATAR", 110, 95);
        g.dispose();
        File file = File.createTempFile("avatar-probe", ".png");
        file.deleteOnExit();
        javax.imageio.ImageIO.write(image, "png", file);

        Platform.startup(() -> {
            Stage stage = new Stage();
            AppWindow window = new AppWindow(stage, "profile-layout", 620, 800, true);
            AppContext ctx = new AppContext(window);
            UserProfile profile = new UserProfile(10001L, "alice", "Alice", file.toURI().toString(),
                    "这个家伙很懒", "女", "2003-04-08", "上海", "学生", 0, System.currentTimeMillis());
            ProfileView view = ProfileView.preview(ctx, profile);
            window.setContent(view, false);
            window.show();

            after(1200, () -> {
                try {
                    javafx.scene.Node node = stage.getScene().getRoot().lookup(".profile-avatar");
                    if (node instanceof javafx.scene.control.Label label) {
                        System.out.printf("avatar label: layout=%.1fx%.1f pref=%.1f min=%.1f graphic=%s%n",
                                label.getWidth(), label.getHeight(), label.getPrefWidth(),
                                label.getMinWidth(),
                                label.getGraphic() == null ? "null"
                                        : String.format("%.1fx%.1f", label.getGraphic().getBoundsInLocal().getWidth(),
                                                label.getGraphic().getBoundsInLocal().getHeight()));
                    } else {
                        System.out.println("avatar node = " + node);
                    }
                    var bounds = stage.getX() + "," + stage.getY();
                    var shot = new java.awt.Robot().createScreenCapture(new java.awt.Rectangle(
                            (int) stage.getX(), (int) stage.getY(),
                            (int) stage.getWidth(), (int) stage.getHeight()));
                    javax.imageio.ImageIO.write(shot, "png", new File("E:\\dsh_workspace\\profile-layout.png"));
                    System.out.println("captured @" + bounds);
                } catch (Exception ex) {
                    System.out.println("capture failed: " + ex);
                }
                Platform.exit();
                System.exit(0);
            });
        });
    }

    private static void after(long millis, Runnable action) {
        new javafx.animation.Timeline(new javafx.animation.KeyFrame(
                javafx.util.Duration.millis(millis), e -> action.run())).play();
    }
}
