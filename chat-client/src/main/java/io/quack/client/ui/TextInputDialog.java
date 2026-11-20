package io.quack.client.ui;

import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.util.function.Consumer;

/** 通用「输入一行文字」弹窗（备注、群名等） */
public final class TextInputDialog {

    private TextInputDialog() {
    }

    public static void show(Window owner, String title, String hint, String initial,
                            Consumer<String> onSave) {
        ModalShell shell = new ModalShell(owner, title, 420, 220);

        Label hintLabel = new Label(hint == null ? "" : hint);
        hintLabel.getStyleClass().add("modal-hint");
        hintLabel.setWrapText(true);

        TextField input = new TextField(initial == null ? "" : initial);
        input.getStyleClass().add("search-field");

        Button cancel = new Button("取消");
        cancel.getStyleClass().add("secondary-button");
        cancel.setOnAction(e -> shell.close());

        Button save = new Button("保存");
        save.getStyleClass().add("primary-button");
        save.setDefaultButton(true);
        save.setOnAction(e -> {
            shell.close();
            onSave.accept(input.getText() == null ? "" : input.getText().trim());
        });

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox actions = new HBox(10, spacer, cancel, save);

        VBox content = new VBox(12, hintLabel, input, actions);
        content.setPadding(new Insets(18));
        content.getStyleClass().add("modal-body");
        VBox.setVgrow(input, Priority.NEVER);

        shell.setContent(content);
        shell.showAndWait();
        javafx.application.Platform.runLater(input::requestFocus);
    }
}
