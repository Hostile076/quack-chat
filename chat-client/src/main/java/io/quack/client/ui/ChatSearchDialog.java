package io.quack.client.ui;

import io.quack.client.store.ClientStore;
import io.quack.client.store.Conversation;
import io.quack.common.model.ChatMessage;
import io.quack.common.model.GroupMember;
import io.quack.common.model.MsgType;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Window;
import javafx.util.StringConverter;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** 查找聊天记录：按关键字、按群成员过滤，并可定位到聊天位置 */
public final class ChatSearchDialog {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("MM-dd HH:mm")
            .withZone(ZoneId.systemDefault());

    private record MemberOption(String id, String label) {
    }

    private ChatSearchDialog() {
    }

    public static void show(Window owner, ClientStore store, Conversation conversation,
                            List<GroupMember> members, Consumer<ChatMessage> onLocate,
                            javafx.scene.Node anchor) {
        if (conversation == null) {
            return;
        }
        ModalShell shell = new ModalShell(owner, "查找聊天记录", 640, 620);

        TextField search = new TextField();
        search.setPromptText("输入关键字，匹配消息内容");
        search.getStyleClass().add("search-field");

        ComboBox<MemberOption> memberBox = new ComboBox<>();
        Popups.install(memberBox);
        if (members != null && !members.isEmpty()) {
            List<MemberOption> options = new ArrayList<>();
            options.add(new MemberOption(null, "全部成员"));
            for (GroupMember member : members) {
                options.add(new MemberOption(member.userId(), member.displayName()));
            }
            memberBox.getItems().setAll(options);
            memberBox.setValue(options.get(0));
            memberBox.setConverter(new StringConverter<>() {
                @Override
                public String toString(MemberOption option) {
                    return option == null ? "全部成员" : option.label();
                }

                @Override
                public MemberOption fromString(String text) {
                    return null;
                }
            });
            memberBox.setMaxWidth(180);
        }
        memberBox.setVisible(members != null && !members.isEmpty());
        memberBox.setManaged(members != null && !members.isEmpty());

        HBox filters = new HBox(8, search, memberBox);
        HBox.setHgrow(search, Priority.ALWAYS);
        filters.setAlignment(Pos.CENTER_LEFT);

        ListView<ChatMessage> results = new ListView<>();
        results.getStyleClass().add("conversation-list");
        results.setCellFactory(view -> new ListCell<>() {
            @Override
            protected void updateItem(ChatMessage message, boolean empty) {
                super.updateItem(message, empty);
                if (empty || message == null) {
                    setGraphic(null);
                    return;
                }
                String who = message.from().equals(store.me() == null ? "" : store.me().id())
                        ? "我" : message.fromName();
                Label content = new Label(who + "：" + preview(message));
                content.getStyleClass().add("conv-title");
                content.setWrapText(true);
                Label time = new Label(TIME.format(Instant.ofEpochMilli(message.ts())));
                time.getStyleClass().add("conv-time");
                VBox texts = new VBox(3, content, time);
                HBox.setHgrow(texts, Priority.ALWAYS);

                Region spacer = new Region();
                HBox.setHgrow(spacer, Priority.ALWAYS);
                Button locate = new Button("定位");
                locate.getStyleClass().add("small-primary-button");
                locate.setOnAction(e -> {
                    if (onLocate != null) {
                        onLocate.accept(message);
                    }
                    shell.close();
                });
                HBox row = new HBox(8, texts, spacer, locate);
                row.setAlignment(Pos.CENTER_LEFT);
                row.setPadding(new Insets(6, 10, 6, 10));
                row.setOnMouseClicked(event -> {
                    if (event.getClickCount() >= 2) {
                        locate.fire();
                    }
                });
                setGraphic(row);
            }
        });
        VBox.setVgrow(results, Priority.ALWAYS);

        Label hint = new Label("只搜索本机缓存的消息；双击或点「定位」跳到聊天位置");
        hint.getStyleClass().add("modal-hint");

        List<ChatMessage> source = store.local() == null
                ? conversation.messages()
                : store.local().loadMessages(conversation.key(), 3000);

        Runnable[] apply = new Runnable[1];
        apply[0] = () -> {
            String keyword = search.getText() == null ? "" : search.getText().trim().toLowerCase();
            MemberOption member = memberBox.getValue();
            String memberId = member == null ? null : member.id();
            if (keyword.isEmpty() && memberId == null) {
                results.getItems().clear();
                hint.setText("只搜索本机缓存的消息；双击或点「定位」跳到聊天位置");
                return;
            }
            List<ChatMessage> matched = source.stream()
                    .filter(m -> memberId == null || memberId.equals(m.from()))
                    .filter(m -> keyword.isEmpty() || searchable(m).toLowerCase().contains(keyword))
                    .limit(300)
                    .toList();
            results.getItems().setAll(matched);
            hint.setText("找到 " + matched.size() + " 条（本机缓存）");
        };
        search.textProperty().addListener((obs, old, keyword) -> apply[0].run());
        memberBox.valueProperty().addListener((obs, old, value) -> apply[0].run());

        VBox content = new VBox(10, filters, hint, results);
        content.setPadding(new Insets(16));
        content.getStyleClass().add("modal-body");
        shell.setContent(content);
        shell.showAbove(anchor);
    }

    /** 聊天记录里的展示文本：图片/视频/文件不显示 URL */
    public static String preview(ChatMessage message) {
        if (message == null) {
            return "";
        }
        MsgType type = message.msgType();
        if (type == null) {
            type = MsgType.TEXT;
        }
        return switch (type) {
            case IMAGE -> "[图片]";
            case VIDEO -> "[视频]";
            case GIFT -> "[礼物]";
            case FILE -> {
                String[] parts = message.content() == null ? new String[0] : message.content().split("\\|");
                yield "[文件] " + (parts.length > 1 ? parts[1] : "");
            }
            case SYSTEM -> message.content() == null ? "[系统消息]" : message.content();
            default -> {
                String text = message.content() == null ? "" : message.content().replace('\n', ' ');
                yield text.length() > 80 ? text.substring(0, 80) + "…" : text;
            }
        };
    }

    private static String searchable(ChatMessage message) {
        String preview = preview(message);
        if (message.msgType() == MsgType.FILE && message.content() != null) {
            return preview + " " + message.content();
        }
        return preview;
    }
}
