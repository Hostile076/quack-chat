package io.quack.client.ui;

import io.quack.client.AppContext;
import io.quack.client.ClientErrors;
import io.quack.client.store.Conversation;
import io.quack.common.model.ChatType;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/** 邀请好友进群：列出还不是群成员的好友，勾选后邀请 */
public final class InviteMemberDialog {

    private InviteMemberDialog() {
    }

    public static void show(Window owner, AppContext ctx, String groupId, Set<String> existingMembers,
                            Consumer<io.quack.common.model.GroupDetail> onDone) {
        ModalShell shell = new ModalShell(owner, "邀请成员", 440, 520);

        List<Conversation> candidates = new ArrayList<>();
        for (Conversation conversation : ctx.store().conversations()) {
            if (conversation.getChatType() == ChatType.PRIVATE
                    && !existingMembers.contains(conversation.getPeerId())) {
                candidates.add(conversation);
            }
        }

        Set<String> selected = new HashSet<>();
        ListView<Conversation> list = new ListView<>(FXCollections.observableArrayList(candidates));
        list.getStyleClass().add("conversation-list");
        list.setPlaceholder(new Label("没有可以邀请的好友了"));
        list.setCellFactory(view -> new ListCell<>() {
            @Override
            protected void updateItem(Conversation conversation, boolean empty) {
                super.updateItem(conversation, empty);
                if (empty || conversation == null) {
                    setGraphic(null);
                    return;
                }
                Label avatar = new Label(Avatars.initial(conversation.getTitle()));
                avatar.getStyleClass().add("avatar");
                avatar.setMinSize(34, 34);
                avatar.setPrefSize(34, 34);
                avatar.setMaxSize(34, 34);
                Avatars.apply(avatar, conversation.getPeerId(), conversation.avatar());

                Label name = new Label(conversation.getTitle());
                name.getStyleClass().add("conv-title");
                Label qc = new Label("QC " + conversation.getPeerId());
                qc.getStyleClass().add("conv-preview");
                VBox texts = new VBox(1, name, qc);
                HBox.setHgrow(texts, Priority.ALWAYS);

                CheckBox check = new CheckBox();
                check.setSelected(selected.contains(conversation.getPeerId()));
                check.selectedProperty().addListener((obs, old, value) -> {
                    if (value) {
                        selected.add(conversation.getPeerId());
                    } else {
                        selected.remove(conversation.getPeerId());
                    }
                });

                HBox row = new HBox(10, avatar, texts, check);
                row.setAlignment(Pos.CENTER_LEFT);
                row.setPadding(new Insets(6, 10, 6, 10));
                row.setOnMouseClicked(e -> check.setSelected(!check.isSelected()));
                setGraphic(row);
            }
        });
        VBox.setVgrow(list, Priority.ALWAYS);

        Button invite = new Button("邀请");
        invite.getStyleClass().add("primary-button");
        invite.setOnAction(e -> {
            if (selected.isEmpty()) {
                Toast.show(invite, "先勾选要邀请的好友");
                return;
            }
            invite.setDisable(true);
            ctx.inviteMembers(groupId, new ArrayList<>(selected)).whenComplete((detail, error) ->
                    Platform.runLater(() -> {
                        invite.setDisable(false);
                        if (error != null) {
                            Toast.show(invite, "邀请失败：" + ClientErrors.describe(error));
                        } else {
                            if (onDone != null) {
                                onDone.accept(detail);
                            }
                            shell.close();
                        }
                    }));
        });

        Label hint = new Label("勾选好友后点邀请，对方会立刻出现在群成员里");
        hint.getStyleClass().add("modal-hint");

        VBox content = new VBox(10, hint, list, invite);
        content.setPadding(new Insets(16));
        content.getStyleClass().add("modal-body");
        shell.setContent(content);
        shell.showAndWait();
    }
}
