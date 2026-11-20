package io.quack.client.ui;

import io.quack.client.AppContext;
import io.quack.client.store.Conversation;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

/** 送礼物：选一个礼物发到当前会话（作为礼物消息） */
public final class GiftDialog {

    private record Gift(String id, String name, String icon) {
    }

    private static final Gift[] GIFTS = {
            new Gift("rose", "玫瑰", "🌹"),
            new Gift("coffee", "咖啡", "☕"),
            new Gift("cake", "蛋糕", "🍰"),
            new Gift("beer", "啤酒", "🍺"),
            new Gift("rocket", "火箭", "🚀"),
            new Gift("crown", "皇冠", "👑")
    };

    private GiftDialog() {
    }

    public static void show(Window owner, AppContext ctx, Conversation conversation, javafx.scene.Node anchor) {
        ModalShell shell = new ModalShell(owner, "送礼物", 420, 320);

        Label hint = new Label("选一个礼物发给 " + conversation.getTitle());
        hint.getStyleClass().add("modal-hint");

        FlowPane grid = new FlowPane(12, 12);
        for (Gift gift : GIFTS) {
            Label icon = new Label(gift.icon());
            icon.getStyleClass().add("tile-icon");
            icon.setStyle("-fx-font-size: 26px;");
            Label name = new Label(gift.name());
            name.getStyleClass().add("tile-sub");

            VBox tile = new VBox(6, icon, name);
            tile.setAlignment(Pos.CENTER);
            tile.getStyleClass().add("tile");
            tile.setPrefSize(100, 86);
            tile.setCursor(javafx.scene.Cursor.HAND);
            tile.setOnMouseClicked(e -> {
                ctx.sendGift(conversation, gift.id(), gift.name(), gift.icon());
                shell.close();
            });
            grid.getChildren().add(tile);
        }

        VBox content = new VBox(14, hint, grid);
        content.setPadding(new Insets(18));
        content.getStyleClass().add("modal-body");
        shell.setContent(content);
        shell.showAbove(anchor);
    }
}
