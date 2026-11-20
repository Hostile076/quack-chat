package io.quack.client.ui;

import io.quack.client.AppContext;
import io.quack.client.ClientErrors;
import io.quack.client.net.FileUploader;
import io.quack.common.model.GroupDetail;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Window;

import java.io.File;
import java.util.function.Consumer;

/**
 * 更换群头像：先在这个弹窗里预览，确认后保存。
 *
 * <p>之前直接弹系统文件选择框，窗口是非模态的，选择框容易跑到主窗口后面，
 * 看起来就是"点了没反应"。改成应用内弹窗后，任何一步都有可见反馈。
 */
public final class GroupAvatarDialog {

    private GroupAvatarDialog() {
    }

    public static void show(Window owner, AppContext ctx, GroupDetail detail,
                            Consumer<GroupDetail> onSaved) {
        ModalShell shell = new ModalShell(owner, "更换群头像", 440, 460);

        Label preview = new Label(Avatars.initial(detail.name()));
        preview.getStyleClass().add("avatar");
        preview.setMinSize(120, 120);
        preview.setPrefSize(120, 120);
        preview.setMaxSize(120, 120);
        Avatars.apply(preview, detail.id(), detail.avatar());

        String[] url = {detail.avatar()};
        Label hint = new Label("建议使用正方形图片，保存后群成员都能看到");
        hint.getStyleClass().add("modal-hint");
        hint.setWrapText(true);

        Button save = new Button("保存");
        save.getStyleClass().add("primary-button");
        // 不要靠「禁用」来表达状态：上传是异步的，容易一直灰着。
        // 直接可点，没选图时给出提示。
        save.setOnAction(e -> {
            if (url[0] == null || url[0].isBlank()) {
                hint.setText("请先点「选择图片…」上传一张图片");
                return;
            }
            save.setDisable(true);
            save.setText("保存中…");
            ctx.updateGroup(detail.id(), null, null, url[0])
                    .whenComplete((updated, error) -> Platform.runLater(() -> {
                        save.setText("保存");
                        if (error != null) {
                            hint.setText("保存失败：" + ClientErrors.describe(error));
                            save.setDisable(false);
                        } else {
                            if (onSaved != null) {
                                onSaved.accept(updated);
                            }
                            shell.close();
                        }
                    }));
        });
        Button choose = new Button("选择图片…");
        choose.getStyleClass().add("secondary-button");
        choose.setOnAction(e -> {
            FileChooser chooser = new FileChooser();
            chooser.setTitle("选择群头像");
            chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(
                    "图片", "*.png", "*.jpg", "*.jpeg", "*.gif", "*.webp"));
            File file = chooser.showOpenDialog(shell.stage());
            if (file == null) {
                return;
            }
            choose.setDisable(true);
            choose.setText("上传中…");
            ctx.uploadFile(io.quack.client.media.ImageShrinker.square(file.toPath(), 256))
                    .whenComplete((uploaded, error) -> Platform.runLater(() -> {
                        choose.setDisable(false);
                        choose.setText("选择图片…");
                        if (error != null) {
                            hint.setText("上传失败：" + ClientErrors.describe(error));
                        } else {
                            url[0] = uploaded.url();
                            Avatars.apply(preview, detail.id(), uploaded.url());
                            hint.setText("已选择新头像，点保存生效");
                            save.setDisable(false);
                        }
                    }));
        });


        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Button cancel = new Button("取消");
        cancel.getStyleClass().add("secondary-button");
        cancel.setOnAction(e -> shell.close());
        HBox actions = new HBox(10, choose, spacer, cancel, save);
        actions.setAlignment(Pos.CENTER_RIGHT);

        VBox content = new VBox(14, preview, hint, actions);
        content.setAlignment(Pos.CENTER);
        content.setPadding(new Insets(20));
        content.getStyleClass().add("modal-body");
        shell.setContent(content);
        shell.showAndWait();
    }

    /** 非群主点群头像时的提示 */
    public static void showNotOwner(Window owner) {
        ModalShell shell = new ModalShell(owner, "提示", 320, 200);
        Label text = new Label("只有群主可以更换群头像");
        text.getStyleClass().add("modal-hint");
        Button ok = new Button("知道了");
        ok.getStyleClass().add("primary-button");
        ok.setOnAction(e -> shell.close());
        VBox content = new VBox(16, text, ok);
        content.setAlignment(Pos.CENTER);
        content.setPadding(new Insets(24));
        content.getStyleClass().add("modal-body");
        shell.setContent(content);
        shell.showAndWait();
    }
}
