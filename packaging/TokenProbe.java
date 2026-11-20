import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.stage.Stage;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;

public class TokenProbe {
    public static void main(String[] args) throws Exception {
        Platform.startup(() -> {
            try {
                String css = ""
                        + ".root { -token-surface: #ff0000; -token-text: #0000ff; }"
                        + ".root.dark { -token-surface: #101010; -token-text: #eeeeee; }"
                        + ".probe { -fx-background-color: -token-surface; -fx-text-fill: -token-text; -fx-padding: 20; -fx-font-size: 20px; }";
                Label label = new Label("HELLO");
                label.getStyleClass().add("probe");
                StackPane root = new StackPane(label);
                root.getStyleClass().add("root");
                java.nio.file.Path cssFile = java.nio.file.Path.of("E:/dsh_workspace/quack-chat/packaging/token-probe.css");
                java.nio.file.Files.writeString(cssFile, css);
                Scene scene = new Scene(root, 200, 100);
                scene.getStylesheets().add(cssFile.toUri().toString());
                Stage stage = new Stage();
                stage.setScene(scene);
                stage.show();
                root.applyCss();
                root.layout();
                Thread.sleep(600);
                printPixel(root, "light");
                root.getStyleClass().add("dark");
                root.applyCss();
                root.layout();
                Thread.sleep(400);
                printPixel(root, "dark");
                stage.close();
            } catch (Throwable t) {
                t.printStackTrace();
            } finally {
                Platform.exit();
                System.exit(0);
            }
        });
        Thread.sleep(6000);
    }

    private static void printPixel(StackPane root, String name) throws Exception {
        Label label = (Label) root.getChildren().get(0);
        System.out.println(name + " bg=" + (label.getBackground() == null ? "null" : label.getBackground().getFills().get(0).getFill())
                + " text=" + label.getTextFill());
        WritableImage img = new WritableImage((int) root.getScene().getWidth(), (int) root.getScene().getHeight());
        root.snapshot(null, img);
        BufferedImage bi = new BufferedImage((int) img.getWidth(), (int) img.getHeight(), BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < bi.getHeight(); y++) {
            for (int x = 0; x < bi.getWidth(); x++) {
                bi.setRGB(x, y, img.getPixelReader().getArgb(x, y));
            }
        }
        ImageIO.write(bi, "png", new File("E:/dsh_workspace/quack-chat/packaging/token-" + name + ".png"));
        Color c = img.getPixelReader().getColor(3, 3);
        System.out.println(name + " corner=" + c);
    }
}
