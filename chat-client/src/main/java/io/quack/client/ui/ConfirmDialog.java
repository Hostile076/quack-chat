package io.quack.client.ui;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

/** 统一风格的确认弹窗（危险操作用） */
public final class ConfirmDialog {

    private ConfirmDialog() {
    }

    public static void confirm(Window owner, String title, String message,
                               String confirmText, Runnable onConfirm) {
        ModalShell shell = new ModalShell(owner, title, 400, 200);

        Label text = new Label(message);
        text.setWrapText(true);
        text.getStyleClass().add("confirm-message");

        Button cancel = new Button("取消");
        cancel.getStyleClass().add("secondary-button");
        cancel.setOnAction(e -> shell.close());

        Button confirm = new Button(confirmText);
        confirm.getStyleClass().add("danger-button");
        confirm.setOnAction(e -> {
            shell.close();
            if (onConfirm != null) {
                onConfirm.run();
            }
        });

        Region spacer = new Region();
        HBox.setHgrow(spacer, javafx.scene.layout.Priority.ALWAYS);

        HBox actions = new HBox(10, spacer, cancel, confirm);
        actions.setAlignment(Pos.CENTER_RIGHT);

        VBox content = new VBox(18, text, actions);
        content.setPadding(new Insets(20));
        content.getStyleClass().add("modal-body");
        shell.setContent(content);
        shell.showAndWait();
    }
}
