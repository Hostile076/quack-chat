package io.quack.client.ui;

import javafx.animation.PauseTransition;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Window;
import javafx.util.Duration;

import java.io.File;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * 图片查看器：滚轮缩放、按住拖动平移、双击复位。
 *
 * <p>之前图片点击是交给系统浏览器打开的，很多机器没有默认图片程序，看起来就是"打不开"，
 * 所以这里自己做了一个内置查看器。
 */
public final class ImageViewerDialog {

    private ImageViewerDialog() {
    }

    public static void show(Window owner, String url) {
        ModalShell shell = new ModalShell(owner, "查看图片", 940, 720);

        ImageView view = new ImageView();
        view.setPreserveRatio(true);
        view.setSmooth(true);

        StackPane viewport = new StackPane(view);
        viewport.getStyleClass().add("image-viewer");
        viewport.setMinSize(0, 0);
        VBox.setVgrow(viewport, Priority.ALWAYS);

        Label zoomLabel = new Label("100%");
        zoomLabel.getStyleClass().add("modal-hint");

        Label status = new Label("滚轮缩放 · 拖动平移 · 双击复位");
        status.getStyleClass().add("modal-hint");

        // 让图片在窗口里按比例铺满
        Runnable fit = () -> {
            double width = Math.max(120, viewport.getWidth() - 24);
            double height = Math.max(120, viewport.getHeight() - 24);
            view.setFitWidth(width);
            view.setFitHeight(height);
        };
        viewport.widthProperty().addListener((obs, old, value) -> fit.run());
        viewport.heightProperty().addListener((obs, old, value) -> fit.run());

        double[] zoom = {1};
        double[] dragStart = new double[2];
        double[] origin = new double[2];

        Runnable reset = () -> {
            zoom[0] = 1;
            view.setScaleX(1);
            view.setScaleY(1);
            view.setTranslateX(0);
            view.setTranslateY(0);
            zoomLabel.setText("100%");
        };

        viewport.addEventHandler(javafx.scene.input.ScrollEvent.SCROLL, event -> {
            double factor = event.getDeltaY() > 0 ? 1.12 : 1 / 1.12;
            // 不设上限：滚轮可以一直放大（下限保留，避免缩到看不见）
            zoom[0] = Math.max(0.05, zoom[0] * factor);
            view.setScaleX(zoom[0]);
            view.setScaleY(zoom[0]);
            zoomLabel.setText(Math.round(zoom[0] * 100) + "%");
            event.consume();
        });

        viewport.addEventHandler(javafx.scene.input.MouseEvent.MOUSE_PRESSED, event -> {
            dragStart[0] = event.getSceneX();
            dragStart[1] = event.getSceneY();
            origin[0] = view.getTranslateX();
            origin[1] = view.getTranslateY();
            viewport.setCursor(Cursor.CLOSED_HAND);
        });

        viewport.addEventHandler(javafx.scene.input.MouseEvent.MOUSE_DRAGGED, event -> {
            view.setTranslateX(origin[0] + event.getSceneX() - dragStart[0]);
            view.setTranslateY(origin[1] + event.getSceneY() - dragStart[1]);
            event.consume();
        });

        viewport.addEventHandler(javafx.scene.input.MouseEvent.MOUSE_RELEASED,
                event -> viewport.setCursor(Cursor.DEFAULT));

        viewport.addEventHandler(javafx.scene.input.MouseEvent.MOUSE_CLICKED, event -> {
            if (event.getButton() == MouseButton.PRIMARY && event.getClickCount() == 2) {
                reset.run();
            }
        });

        Button zoomOut = new Button("－");
        zoomOut.getStyleClass().add("secondary-button");
        zoomOut.setOnAction(e -> {
            zoom[0] = Math.max(0.05, zoom[0] / 1.2);
            view.setScaleX(zoom[0]);
            view.setScaleY(zoom[0]);
            zoomLabel.setText(Math.round(zoom[0] * 100) + "%");
        });

        Button zoomIn = new Button("＋");
        zoomIn.getStyleClass().add("secondary-button");
        zoomIn.setOnAction(e -> {
            zoom[0] = zoom[0] * 1.2;
            view.setScaleX(zoom[0]);
            view.setScaleY(zoom[0]);
            zoomLabel.setText(Math.round(zoom[0] * 100) + "%");
        });

        Button actual = new Button("1:1");
        actual.getStyleClass().add("secondary-button");
        actual.setOnAction(e -> {
            zoom[0] = 1;
            view.setScaleX(1);
            view.setScaleY(1);
            view.setTranslateX(0);
            view.setTranslateY(0);
            zoomLabel.setText("100%");
        });

        Button save = new Button("另存为…");
        save.getStyleClass().add("secondary-button");
        save.setOnAction(e -> saveAs(shell, url, status));

        Button browser = new Button("用浏览器打开");
        browser.getStyleClass().add("secondary-button");
        browser.setOnAction(e -> openInBrowser(url));

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Button close = new Button("关闭");
        close.getStyleClass().add("primary-button");
        close.setOnAction(e -> shell.close());

        HBox toolbar = new HBox(8, zoomOut, zoomIn, actual, zoomLabel, spacer, save, browser, close);
        toolbar.setAlignment(Pos.CENTER_LEFT);
        toolbar.setPadding(new Insets(4, 0, 0, 0));

        VBox content = new VBox(10, viewport, status, toolbar);
        content.setPadding(new Insets(14));
        content.getStyleClass().add("modal-body");
        shell.setContent(content);

        Image image = new Image(url, true);
        image.progressProperty().addListener((obs, old, progress) -> {
            if (progress.doubleValue() < 1 && image.getProgress() < 1) {
                status.setText("加载中… " + Math.round(image.getProgress() * 100) + "%");
            }
        });
        image.errorProperty().addListener((obs, old, failed) -> {
            if (Boolean.TRUE.equals(failed)) {
                status.setText("图片加载失败：" + url);
            }
        });
        view.setImage(image);
        image.progressProperty().addListener((obs, old, progress) -> {
            if (progress.doubleValue() >= 1) {
                status.setText("滚轮缩放 · 拖动平移 · 双击复位");
                fit.run();
            }
        });

        shell.showAndWait();
        // 首帧之后按视口大小适配一次
        PauseTransition pause = new PauseTransition(Duration.millis(120));
        pause.setOnFinished(e -> fit.run());
        pause.play();
    }

    private static void saveAs(ModalShell shell, String url, Label status) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("另存图片");
        chooser.setInitialFileName("image.png");
        File target = chooser.showSaveDialog(shell.stage());
        if (target == null) {
            return;
        }
        try (InputStream in = HttpClient.newHttpClient().send(
                        HttpRequest.newBuilder(URI.create(url)).GET().build(),
                        HttpResponse.BodyHandlers.ofInputStream())
                .body()) {
            Files.copy(in, Path.of(target.toURI()), StandardCopyOption.REPLACE_EXISTING);
            status.setText("已保存到 " + target.getAbsolutePath());
        } catch (Exception ex) {
            status.setText("保存失败：" + ex.getMessage());
        }
    }

    private static void openInBrowser(String url) {
        try {
            java.awt.Desktop.getDesktop().browse(URI.create(url));
        } catch (Exception ignored) {
            // 没有默认浏览器就算了，用户还可以另存
        }
    }
}
