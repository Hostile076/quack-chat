import io.quack.client.ui.Theme;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;

public class PopupThemeProbe {
    public static void main(String[] args) throws Exception {
        Platform.startup(() -> {
            try {
                Label owner = new Label("owner");
                StackPane root = new StackPane(owner);
                root.getStyleClass().add("root");
                root.getStyleClass().add("dark");
                Scene scene = new Scene(root, 320, 200);
                scene.getStylesheets().add(PopupThemeProbe.class.getResource("/app.css").toExternalForm());
                Stage stage = new Stage();
                stage.setScene(scene);
                stage.show();
                root.applyCss();
                root.layout();

                ContextMenu menu = new ContextMenu();
                MenuItem item = new MenuItem("查看资料");
                menu.getItems().add(item);
                menu.show(owner, 40, 40);
                Thread.sleep(500);
                System.out.println("menu class=" + menu.getStyleClass());
                if (menu.getScene() != null) {
                    var node = menu.getScene().getRoot();
                    WritableImage img = node.snapshot(null, null);
                    write(img, "E:/dsh_workspace/quack-chat/packaging/ctx-dark.png");
                    System.out.println("menu snapshot " + img.getWidth() + "x" + img.getHeight()
                            + " corner=" + img.getPixelReader().getColor(2, 2)
                            + " center=" + img.getPixelReader().getColor((int) img.getWidth() / 2,
                                    (int) img.getHeight() / 2));
                }
                menu.hide();

                ComboBox<String> combo = new ComboBox<>();
                combo.getItems().addAll("Alice", "Bob");
                combo.setValue("Alice");
                root.getChildren().add(combo);
                root.applyCss();
                root.layout();
                combo.show();
                Thread.sleep(400);
                var popupList = combo.getSkin() == null ? null : combo.lookup(".combo-box-popup");
                System.out.println("combo popup node=" + popupList);
                if (combo.getScene() != null) {
                    WritableImage img = combo.snapshot(null, null);
                    write(img, "E:/dsh_workspace/quack-chat/packaging/combo-dark.png");
                }
                combo.hide();
                stage.close();
            } catch (Throwable t) {
                t.printStackTrace();
            } finally {
                Platform.exit();
                System.exit(0);
            }
        });
        Thread.sleep(8000);
    }

    private static void write(WritableImage img, String path) throws Exception {
        BufferedImage bi = new BufferedImage((int) img.getWidth(), (int) img.getHeight(), BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < bi.getHeight(); y++) {
            for (int x = 0; x < bi.getWidth(); x++) {
                bi.setRGB(x, y, img.getPixelReader().getArgb(x, y));
            }
        }
        ImageIO.write(bi, "png", new File(path));
    }
}
