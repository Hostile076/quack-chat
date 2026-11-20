package io.quack.client.ui;

import io.quack.client.AppContext;
import io.quack.client.ClientErrors;
import io.quack.client.store.Conversation;
import io.quack.client.store.ConversationPrefs;
import io.quack.common.model.GroupDetail;
import io.quack.common.model.GroupMember;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tooltip;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.util.function.Consumer;

/**
 * 群主页（参考 QQ 群资料页）。
 *
 * <p>从上到下：群头像/群号 → 群成员九宫格 → 群名称/群公告 →
 * 群应用 → 群快捷栏/查找记录 → 置顶/隐藏/免打扰开关 → 我在本群的昵称 →
 * 删除聊天记录 / 退出群聊。
 *
 * <p>群应用、群快捷栏、找聊天记录、通知设置这些涉及服务端大功能的入口
 * 先做成占位（点击给“接口预留”提示），其余全部可真实操作。
 */
public final class GroupInfoView extends BorderPane {

    private final AppContext ctx;
    private final Conversation conversation;
    private final ConversationPrefs prefs;
    private final Runnable onPrefsChanged;
    private final Runnable onClose;
    private final java.util.function.Consumer<String> onInsertPhrase;
    private final java.util.function.Consumer<io.quack.common.model.ChatMessage> onLocate;
    private final VBox content = new VBox(12);

    private GroupDetail detail;

    private GroupInfoView(AppContext ctx, Conversation conversation, Runnable onPrefsChanged, Runnable onClose,
                          GroupDetail preloaded, java.util.function.Consumer<String> onInsertPhrase,
                          java.util.function.Consumer<io.quack.common.model.ChatMessage> onLocate) {
        this.ctx = ctx;
        this.conversation = conversation;
        this.prefs = ctx.store().prefs();
        this.onPrefsChanged = onPrefsChanged;
        this.onClose = onClose;
        this.onInsertPhrase = onInsertPhrase;
        this.onLocate = onLocate;
        this.detail = preloaded;
        getStyleClass().add("profile-root");

        ScrollPane scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("profile-scroll");
        setCenter(scroll);

        content.setPadding(new Insets(0, 0, 18, 0));
        Label loading = new Label("加载群资料中…");
        loading.getStyleClass().add("empty-state");
        VBox box = new VBox(loading);
        box.setAlignment(Pos.CENTER);
        box.setPadding(new Insets(80, 20, 20, 20));
        content.getChildren().add(box);

        if (preloaded != null) {
            render();
        } else {
            load();
        }
    }

    /** 供截图脚本离线渲染用 */
    public static GroupInfoView preview(AppContext ctx, Conversation conversation, GroupDetail detail) {
        return new GroupInfoView(ctx, conversation, null, () -> {
        }, detail, null, null);
    }

    public static void show(Window owner, AppContext ctx, Conversation conversation, Runnable onPrefsChanged,
                            java.util.function.Consumer<String> onInsertPhrase) {
        show(owner, ctx, conversation, onPrefsChanged, onInsertPhrase, null);
    }

    public static void show(Window owner, AppContext ctx, Conversation conversation, Runnable onPrefsChanged,
                            java.util.function.Consumer<String> onInsertPhrase,
                            java.util.function.Consumer<io.quack.common.model.ChatMessage> onLocate) {
        ModalShell shell = new ModalShell(owner, "聊天信息", 620, 860, false);
        GroupInfoView view = new GroupInfoView(ctx, conversation, onPrefsChanged, shell::close, null,
                onInsertPhrase, onLocate);
        shell.setContent(view);
        shell.showAndWait();
    }

    private void load() {
        ctx.loadGroupDetail(conversation.getPeerId()).whenComplete((result, error) -> Platform.runLater(() -> {
            if (error != null) {
                Label label = new Label("加载失败：" + ClientErrors.describe(error));
                label.getStyleClass().add("empty-state");
                VBox box = new VBox(label);
                box.setAlignment(Pos.CENTER);
                box.setPadding(new Insets(80, 20, 20, 20));
                content.getChildren().setAll(box);
                return;
            }
            this.detail = result;
            render();
        }));
    }

    private void render() {
        content.getChildren().setAll(
                header(),
                membersCard(),
                infoCard(),
                appsCard(),
                quickRows(),
                switchesCard(),
                aliasRow(),
                dangerZone());
    }

    // ------------------------------------------------------------------
    // 顶部
    // ------------------------------------------------------------------

    private HBox header() {
        Label avatar = new Label(Avatars.initial(detail.name()));
        avatar.getStyleClass().addAll("avatar", "group-avatar");
        avatar.setMinSize(64, 64);
        avatar.setPrefSize(64, 64);
        avatar.setMaxSize(64, 64);
        Avatars.apply(avatar, detail.id(), detail.avatar());
        // 群主点头像可以换群头像
        // 用 StackPane 兜住整个头像区域，避免点空白处没反应
        StackPane avatarHolder = new StackPane(avatar);
        avatarHolder.setMinSize(64, 64);
        avatarHolder.setPrefSize(64, 64);
        avatarHolder.setMaxSize(64, 64);
        avatarHolder.setCursor(javafx.scene.Cursor.HAND);
        avatarHolder.setOnMouseClicked(e -> {
            if (isManager()) {
                changeGroupAvatar();
            } else {
                GroupAvatarDialog.showNotOwner(getScene() == null ? null : getScene().getWindow());
            }
        });
        Tooltip.install(avatarHolder, Popups.tooltip(isManager() ? "点击更换群头像" : "只有群主或管理员可以更换群头像"));

        Label name = new Label(detail.name());
        name.getStyleClass().add("profile-name");
        Label number = new Label("群号：" + detail.id());
        number.getStyleClass().add("profile-sub");
        VBox texts = new VBox(3, name, number);
        texts.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(texts, Priority.ALWAYS);

        Button more = new Button("⋯");
        more.getStyleClass().add("title-button");
        more.setOnAction(e -> {
            javafx.scene.control.ContextMenu menu = new javafx.scene.control.ContextMenu();
            javafx.scene.control.MenuItem invite = new javafx.scene.control.MenuItem("邀请成员");
            invite.setOnAction(x -> InviteMemberDialog.show(getScene().getWindow(), ctx, detail.id(),
                    memberIds(), updated -> {
                        this.detail = updated;
                        render();
                    }));
            javafx.scene.control.MenuItem essence = new javafx.scene.control.MenuItem("精华消息");
            essence.setOnAction(x -> EssenceDialog.show(getScene().getWindow(), ctx, detail.id(), isManager()));
            menu.getItems().add(essence);
            if (isManager()) {
                menu.getItems().add(0, invite);
            }
            menu.show(more, javafx.geometry.Side.BOTTOM, 0, 4);
        });

        HBox header = new HBox(16, avatarHolder, texts, more);
        header.setAlignment(Pos.CENTER_LEFT);
        header.setPadding(new Insets(18, 20, 12, 20));
        header.getStyleClass().add("group-header");
        return header;
    }

    // ------------------------------------------------------------------
    // 群成员
    // ------------------------------------------------------------------

    private VBox membersCard() {
        Label title = new Label("群成员");
        title.getStyleClass().add("card-title");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Label count = new Label(detail.memberCount() + " 人  ›");
        count.getStyleClass().add("card-action");
        count.setCursor(javafx.scene.Cursor.HAND);
        // 右上角「3 人 ›」：切到群成员页（按拼音首字母分组）
        count.setOnMouseClicked(e -> showMembersPage());

        HBox titleRow = new HBox(8, title, spacer, count);
        titleRow.setAlignment(Pos.CENTER_LEFT);

        FlowPane grid = new FlowPane(10, 12);
        grid.setPrefWrapLength(520);
        for (GroupMember member : detail.members()) {
            grid.getChildren().add(memberTile(member));
        }
        if (isManager()) {
            grid.getChildren().add(inviteTile());
        }

        VBox card = new VBox(12, titleRow, grid);
        card.getStyleClass().add("info-card");
        card.setPadding(new Insets(14, 18, 16, 18));
        return card;
    }

    /** 群主更换群头像 */
    private void changeGroupAvatar() {
        GroupAvatarDialog.show(getScene() == null ? null : getScene().getWindow(), ctx, detail,
                updated -> {
                    this.detail = updated;
                    render();
                });
    }
    /** 群成员页：按拼音首字母分组，A→Z，非中文归到 # */
    private void showMembersPage() {
        if (detail == null) {
            return;
        }
        java.util.List<GroupMember> sorted = new java.util.ArrayList<>(detail.members());
        java.text.Collator collator = java.text.Collator.getInstance(java.util.Locale.CHINA);
        sorted.sort(java.util.Comparator
                .comparing((GroupMember m) -> initialOf(m.nickname()))
                .thenComparing(GroupMember::nickname, collator));

        Button back = new Button("‹ 返回");
        back.getStyleClass().add("title-button");
        back.setOnAction(e -> render());
        Label title = new Label("群成员 · " + detail.memberCount());
        title.getStyleClass().add("card-title");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox header = new HBox(8, back, title, spacer);
        header.setAlignment(Pos.CENTER_LEFT);

        VBox list = new VBox(4);
        String current = null;
        for (GroupMember member : sorted) {
            String initial = initialOf(member.nickname());
            if (!initial.equals(current)) {
                current = initial;
                Label section = new Label(initial);
                section.getStyleClass().add("member-section");
                list.getChildren().add(section);
            }
            list.getChildren().add(memberRow(member));
        }

        ScrollPane scroll = new ScrollPane(list);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("edge-to-edge");
        VBox.setVgrow(scroll, Priority.ALWAYS);

        content.getChildren().setAll(header, scroll);
    }

    private HBox memberRow(GroupMember member) {
        Label avatar = new Label(Avatars.initial(member.nickname()));
        avatar.getStyleClass().add("avatar");
        avatar.setMinSize(36, 36);
        avatar.setPrefSize(36, 36);
        avatar.setMaxSize(36, 36);
        Avatars.apply(avatar, member.userId(), member.avatar());

        Label name = new Label(member.nickname());
        name.getStyleClass().add("conv-title");
        Label role = new Label(member.isOwner() ? "群主"
                : GroupMember.ADMIN.equals(member.role()) ? "管理员" : "成员");
        role.getStyleClass().add("chip");
        VBox texts = new VBox(2, name, role);
        HBox.setHgrow(texts, Priority.ALWAYS);

        HBox row = new HBox(10, avatar, texts);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(8, 10, 8, 10));
        row.getStyleClass().add("option-row");
        row.setCursor(javafx.scene.Cursor.HAND);
        row.setOnMouseClicked(e -> ProfileView.show(getScene().getWindow(), ctx, member.userId(), null));
        return row;
    }

    private static String initialOf(String name) {
        if (name == null || name.isBlank()) {
            return "#";
        }
        char first = name.trim().charAt(0);
        if (first >= 'a' && first <= 'z') {
            return String.valueOf(Character.toUpperCase(first));
        }
        if (first >= 'A' && first <= 'Z') {
            return String.valueOf(first);
        }
        try {
            String[] pinyin = net.sourceforge.pinyin4j.PinyinHelper.toHanyuPinyinStringArray(first);
            if (pinyin != null && pinyin.length > 0 && !pinyin[0].isEmpty()) {
                return String.valueOf(Character.toUpperCase(pinyin[0].charAt(0)));
            }
        } catch (Exception ignored) {
            // 落回 #
        }
        return "#";
    }

    private VBox memberTile(GroupMember member) {
        Label avatar = new Label(Avatars.initial(member.nickname()));
        avatar.getStyleClass().add("avatar");
        avatar.setMinSize(40, 40);
        avatar.setPrefSize(40, 40);
        avatar.setMaxSize(40, 40);
        Avatars.apply(avatar, member.userId(), member.avatar());

        String suffix = member.isOwner() ? "（群主）"
                : GroupMember.ADMIN.equals(member.role()) ? "（管理员）" : "";
        Label name = new Label(member.displayName() + suffix);
        name.getStyleClass().add("member-name");
        name.setMaxWidth(70);
        name.setTextOverrun(javafx.scene.control.OverrunStyle.ELLIPSIS);

        VBox tile = new VBox(4, avatar, name);
        tile.setAlignment(Pos.CENTER);
        tile.setPrefWidth(70);
        tile.setCursor(javafx.scene.Cursor.HAND);
        tile.setOnMouseClicked(e -> ProfileView.show(getScene().getWindow(), ctx, member.userId(), null));
        tile.setOnContextMenuRequested(event -> {
            if ((isManager() || isOwner()) && !member.userId().equals(myId())
                    && !member.isOwner()) {
                javafx.scene.control.ContextMenu menu = new javafx.scene.control.ContextMenu();
                javafx.scene.control.MenuItem kick = new javafx.scene.control.MenuItem("移出群聊");
                kick.setOnAction(e -> ctx.kickMember(detail.id(), member.userId())
                        .whenComplete((updated, error) -> Platform.runLater(() -> {
                            if (error == null) {
                                detail = updated;
                                render();
                            } else {
                                Toast.show(this, "操作失败：" + ClientErrors.describe(error));
                            }
                        })));
                menu.getItems().add(kick);
                if (isOwner()) {
                    javafx.scene.control.MenuItem transfer = new javafx.scene.control.MenuItem("转让群主");
                    transfer.setOnAction(e -> ConfirmDialog.confirm(getScene().getWindow(), "转让群主",
                            "转让后你将失去群主权限，确定吗？", "转让", () ->
                                    ctx.transferOwner(detail.id(), member.userId())
                                            .whenComplete((updated, error) -> Platform.runLater(() -> {
                                                if (error == null) {
                                                    detail = updated;
                                                    render();
                                                }
                                            }))));
                    menu.getItems().add(transfer);
                }
                menu.show(tile, event.getScreenX(), event.getScreenY());
                event.consume();
            }
        });
        return tile;
    }

    private boolean isOwner() {
        return detail != null && ctx.store().me() != null
                && detail.ownerId().equals(ctx.store().me().id());
    }

    /** 群主或管理员 */
    private boolean isManager() {
        if (detail == null || ctx.store().me() == null) {
            return false;
        }
        if (isOwner()) {
            return true;
        }
        String me = ctx.store().me().id();
        return detail.members() != null && detail.members().stream()
                .anyMatch(member -> me.equals(member.userId())
                        && GroupMember.ADMIN.equals(member.role()));
    }

    private String myId() {
        return ctx.store().me() == null ? "" : ctx.store().me().id();
    }

    private java.util.Set<String> memberIds() {
        java.util.Set<String> ids = new java.util.HashSet<>();
        detail.members().forEach(m -> ids.add(m.userId()));
        return ids;
    }

    private VBox inviteTile() {
        Label plus = new Label("＋");
        plus.getStyleClass().addAll("avatar", "invite-avatar");
        plus.setMinSize(40, 40);
        plus.setPrefSize(40, 40);
        plus.setMaxSize(40, 40);
        Label label = new Label("邀请");
        label.getStyleClass().add("member-name");

        VBox tile = new VBox(4, plus, label);
        tile.setAlignment(Pos.CENTER);
        tile.setPrefWidth(70);
        tile.setCursor(javafx.scene.Cursor.HAND);
        tile.setOnMouseClicked(e -> InviteMemberDialog.show(getScene().getWindow(), ctx, detail.id(),
                memberIds(), updated -> {
                    this.detail = updated;
                    render();
                }));
        return tile;
    }

    // ------------------------------------------------------------------
    // 群资料
    // ------------------------------------------------------------------

    private VBox infoCard() {
        VBox card = new VBox();
        card.getStyleClass().add("info-card");
        card.getChildren().addAll(
                settingRow("群名称", detail.name(), () -> editGroupField("群名称", detail.name(), true)),
                settingRow("群公告", announcementValue(), () ->
                        GroupAnnouncementListDialog.show(getScene().getWindow(), ctx, detail, isManager(),
                                updated -> {
                                    this.detail = updated;
                                    render();
                                })),
                settingRow("群管理", adminCount() + " 名管理员", () ->
                        GroupAdminListDialog.show(getScene().getWindow(), ctx, detail, isOwner(),
                                updated -> {
                                    this.detail = updated;
                                    render();
                                })));
        return card;
    }

    private long adminCount() {
        return detail.members() == null ? 0 : detail.members().stream()
                .filter(member -> GroupMember.ADMIN.equals(member.role())).count();
    }

    private String announcementValue() {
        var list = detail.announcementList();
        if (list.isEmpty()) {
            return detail.announcement() == null || detail.announcement().isBlank()
                    ? "未设置" : detail.announcement();
        }
        String latest = list.get(0).text() == null ? "" : list.get(0).text().replace('\n', ' ');
        if (latest.length() > 18) {
            latest = latest.substring(0, 18) + "…";
        }
        return "共 " + list.size() + " 条 · " + latest;
    }

    private HBox settingRow(String title, String value, Runnable onClick) {
        Label name = new Label(title);
        name.getStyleClass().add("option-title");
        name.setMinWidth(70);

        Label valueLabel = new Label(value);
        valueLabel.getStyleClass().add("option-value");
        valueLabel.setMaxWidth(380);
        valueLabel.setTextOverrun(javafx.scene.control.OverrunStyle.ELLIPSIS);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Label arrow = new Label("›");
        arrow.getStyleClass().add("option-arrow");

        HBox row = new HBox(10, name, spacer, valueLabel, arrow);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("setting-row");
        row.setPadding(new Insets(14, 18, 14, 18));
        row.setCursor(javafx.scene.Cursor.HAND);
        row.setOnMouseClicked(e -> onClick.run());
        return row;
    }

    private void editGroupField(String field, String value, boolean isName) {
        boolean owner = detail.ownerId() != null && ctx.me() != null
                && detail.ownerId().equals(ctx.me().id());
        if (!isManager()) {
            Toast.show(this, "只有群主或管理员可以修改" + field);
            return;
        }
        EditFieldDialog.show(getScene().getWindow(), "修改" + field, field, value, text -> {
            String name = isName ? text : null;
            String announcement = isName ? null : text;
            ctx.updateGroup(detail.id(), name, announcement).whenComplete((updated, error) ->
                    Platform.runLater(() -> {
                        if (error != null) {
                            Toast.show(this, "保存失败：" + ClientErrors.describe(error));
                        } else {
                            this.detail = updated;
                            render();
                        }
                    }));
        });
    }

    // ------------------------------------------------------------------
    // 群应用 / 快捷入口
    // ------------------------------------------------------------------

    private VBox appsCard() {
        Label title = new Label("群应用");
        title.getStyleClass().add("card-title");

        HBox apps = new HBox(18);
        apps.getChildren().addAll(
                appTile("folder", "文件", () -> FilesDialog.show(getScene().getWindow(), conversation,
                        FilesDialog.Mode.FILES)),
                appTile("image", "相册", () -> FilesDialog.show(getScene().getWindow(), conversation,
                        FilesDialog.Mode.IMAGES)),
                appTile("star", "精华消息", () -> EssenceDialog.show(getScene().getWindow(), ctx,
                        detail.id(), isManager())));

        VBox card = new VBox(12, title, apps);
        card.getStyleClass().add("info-card");
        card.setPadding(new Insets(14, 18, 16, 18));
        return card;
    }

    private VBox appTile(String iconName, String name, Runnable action) {
        Label iconLabel = new Label();
        iconLabel.setGraphic(Icons.of(iconName, 22));
        Label label = new Label(name);
        label.getStyleClass().add("tile-sub");
        VBox tile = new VBox(4, iconLabel, label);
        tile.setAlignment(Pos.CENTER);
        tile.setPrefWidth(72);
        tile.setCursor(javafx.scene.Cursor.HAND);
        tile.setOnMouseClicked(e -> action.run());
        return tile;
    }

    private VBox quickRows() {
        VBox card = new VBox();
        card.getStyleClass().add("info-card");
        card.getChildren().addAll(
                settingRow("群快捷栏", "常用短语，一键插入输入框", () ->
                        QuickPhraseDialog.show(getScene().getWindow(), text -> {
                            if (onInsertPhrase != null) {
                                onInsertPhrase.accept(text);
                            }
                        }, null)),
                settingRow("查找聊天记录", "按关键字搜索本群消息", () ->
                        ChatSearchDialog.show(getScene().getWindow(), ctx.store(), conversation,
                                detail.members(), onLocate, null)));
        return card;
    }

    // ------------------------------------------------------------------
    // 开关
    // ------------------------------------------------------------------

    private VBox switchesCard() {
        String key = conversation.key();
        VBox card = new VBox();
        card.getStyleClass().add("info-card");
        card.getChildren().addAll(
                switchRow("设为置顶", prefs.isPinned(key), value -> {
                    prefs.setPinned(key, value);
                    // 置顶要真的重新排序，而不只是存个开关
                    ctx.store().resort();
                    if (onPrefsChanged != null) {
                        onPrefsChanged.run();
                    }
                }),
                switchRow("隐藏会话", prefs.isHidden(key), value -> {
                    prefs.setHidden(key, value);
                    if (onPrefsChanged != null) {
                        onPrefsChanged.run();
                    }
                    if (value) {
                        Toast.show(this, "已隐藏，可在左下角账号菜单里重新显示");
                    }
                }),
                switchRow("消息免打扰", prefs.isMuted(key), value -> prefs.setMuted(key, value)),
                switchRow("消息提示音", prefs.isNotifySound(), prefs::setNotifySound));
        return card;
    }

    private HBox switchRow(String title, boolean initial, Consumer<Boolean> onChange) {
        Label name = new Label(title);
        name.getStyleClass().add("option-title");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        ToggleSwitch toggle = new ToggleSwitch(initial);
        toggle.selectedProperty().addListener((obs, old, value) -> onChange.accept(value));

        HBox row = new HBox(10, name, spacer, toggle);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("setting-row");
        row.setPadding(new Insets(12, 18, 12, 18));
        return row;
    }

    // ------------------------------------------------------------------
    // 我在本群的昵称 / 危险操作
    // ------------------------------------------------------------------

    private VBox aliasRow() {
        GroupMember me = detail.members() == null ? null : detail.members().stream()
                .filter(member -> myId().equals(member.userId()))
                .findFirst().orElse(null);
        String current = me == null || me.alias() == null ? "" : me.alias();
        String value = current.isBlank() ? "未设置" : current;

        VBox card = new VBox();
        card.getStyleClass().add("info-card");
        card.getChildren().add(settingRow("我在本群的昵称", value, () ->
                EditFieldDialog.show(getScene().getWindow(), "我在本群的昵称", "最多 20 个字，留空恢复昵称",
                        current, text -> ctx.setGroupAlias(detail.id(), text)
                                .whenComplete((updated, error) -> Platform.runLater(() -> {
                                    if (error != null) {
                                        Toast.show(this, "保存失败：" + ClientErrors.describe(error));
                                    } else {
                                        this.detail = updated;
                                        render();
                                    }
                                })))));
        return card;
    }

    private VBox dangerZone() {
        VBox card = new VBox();
        card.getStyleClass().add("info-card");
        card.getChildren().addAll(
                dangerRow("删除聊天记录", false, () -> ConfirmDialog.confirm(getScene().getWindow(),
                        "删除聊天记录", "只删除本机这个群的聊天记录，对方不受影响。", "删除",
                        () -> {
                            ctx.store().clearMessages(conversation);
                            Toast.show(this, "已删除本机聊天记录");
                        })),
                dangerRow("退出群聊", true, () -> ConfirmDialog.confirm(getScene().getWindow(),
                        "退出群聊", "退出后将不再接收该群消息。", "退出",
                        () -> ctx.quitGroup(detail.id()).whenComplete((ignored, error) ->
                                Platform.runLater(() -> {
                                    if (error != null) {
                                        Toast.show(this, "操作失败：" + ClientErrors.describe(error));
                                    } else {
                                        onClose.run();
                                    }
                                })))));
        if (isOwner()) {
            card.getChildren().add(dangerRow("解散群聊", true, () ->
                    ConfirmDialog.confirm(getScene().getWindow(), "解散群聊",
                            "解散后所有成员都会被移出，聊天记录无法恢复。确定解散吗？", "解散",
                            () -> ctx.dissolveGroup(detail.id()).whenComplete((ignored, error) ->
                                    Platform.runLater(() -> {
                                        if (error != null) {
                                            Toast.show(this, "解散失败：" + ClientErrors.describe(error));
                                        } else {
                                            onClose.run();
                                        }
                                    })))));
        }
        return card;
    }

    private Region dangerRow(String title, boolean strong, Runnable onClick) {
        Label label = new Label(title);
        label.getStyleClass().add(strong ? "danger-text-strong" : "danger-text");
        HBox row = new HBox(label);
        row.setPadding(new Insets(14, 18, 14, 18));
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("setting-row");
        row.setCursor(javafx.scene.Cursor.HAND);
        row.setOnMouseClicked(e -> onClick.run());
        return row;
    }

    /** 单行文本编辑弹窗 */
    private static final class EditFieldDialog {

        static void show(Window owner, String title, String hint, String initial, Consumer<String> onSave) {
            ModalShell shell = new ModalShell(owner, title, 420, 210);

            Label hintLabel = new Label(hint);
            hintLabel.getStyleClass().add("modal-hint");

            TextField field = new TextField(initial == null ? "" : initial);
            field.getStyleClass().add("login-field");
            field.setMaxWidth(Double.MAX_VALUE);

            Button cancel = new Button("取消");
            cancel.getStyleClass().add("secondary-button");
            cancel.setOnAction(e -> shell.close());

            Button save = new Button("保存");
            save.getStyleClass().add("primary-button");
            save.setDefaultButton(true);
            save.setOnAction(e -> {
                shell.close();
                onSave.accept(field.getText() == null ? "" : field.getText().trim());
            });

            HBox actions = new HBox(10, cancel, save);
            actions.setAlignment(Pos.CENTER_RIGHT);

            VBox content = new VBox(12, hintLabel, field, actions);
            content.setPadding(new Insets(18));
            content.getStyleClass().add("modal-body");
            shell.setContent(content);
            shell.showAndWait();
        }
    }
}
