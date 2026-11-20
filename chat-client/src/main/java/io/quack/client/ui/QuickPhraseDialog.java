package io.quack.client.ui;

import javafx.geometry.Insets;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.util.List;
import java.util.function.Consumer;

/** 群快捷栏：常用短语，点一下插入输入框 */
public final class QuickPhraseDialog {

    private static final List<String> PHRASES = List.of(
            "收到，我看下",
            "稍等，马上处理",
            "这个我下午回复你",
            "大家晚上八点碰一下？",
            "已经提交了，麻烦帮忙 review",
            "辛苦了 👍");

    private QuickPhraseDialog() {
    }

    public static void show(Window owner, Consumer<String> onPick, javafx.scene.Node anchor) {
        ModalShell shell = new ModalShell(owner, "快捷短语", 380, 420);

        ListView<String> list = new ListView<>(javafx.collections.FXCollections.observableArrayList(PHRASES));
        list.getStyleClass().add("conversation-list");
        list.setCellFactory(view -> new ListCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setGraphic(null);
                    return;
                }
                Label label = new Label(item);
                label.getStyleClass().add("conv-title");
                VBox box = new VBox(label);
                box.setPadding(new Insets(8, 10, 8, 10));
                setGraphic(box);
            }
        });
        list.setOnMouseClicked(e -> {
            String selected = list.getSelectionModel().getSelectedItem();
            if (selected != null) {
                shell.close();
                onPick.accept(selected);
            }
        });

        Label hint = new Label("点击一条即可插入输入框");
        hint.getStyleClass().add("modal-hint");

        VBox content = new VBox(10, hint, list);
        VBox.setVgrow(list, javafx.scene.layout.Priority.ALWAYS);
        content.setPadding(new Insets(16));
        content.getStyleClass().add("modal-body");
        shell.setContent(content);
        shell.showAbove(anchor);
    }
}
