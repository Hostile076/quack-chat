package io.quack.client.ui;

import io.quack.client.AppContext;
import io.quack.client.ClientErrors;
import io.quack.common.model.Moment;
import io.quack.common.model.MomentComment;
import io.quack.common.model.MomentMedia;
import io.quack.common.model.User;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Window;

import java.io.File;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * QC 空间：好友动态 / 个人空间 / 相册、图文/视频动态、可见范围、点赞（唯一可取消）、
 * 点赞列表、评论与回复、转发、编辑、删除、分页、统计。
 *
 * <p>参考 OSSN 的 wall post 模型。视频只显示封面缩略图，播放交给系统播放器。
 */
public final class MomentSpaceView {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("MM-dd HH:mm")
            .withZone(ZoneId.systemDefault());
    private static final int PAGE_SIZE = 10;
    private static final Map<String, String> VISIBILITY = new LinkedHashMap<>() {{
        put("公开", "PUBLIC");
        put("好友", "FRIENDS");
        put("仅自己", "PRIVATE");
    }};

    private final AppContext ctx;
    private final ModalShell shell;
    private final String spaceQcId;
    private final boolean self;
    private final boolean timeline;
    private final String spaceName;
    private Label headerTitle;
    private Label headerSub;

    private final VBox feed = new VBox(12);
    private final FlowPane albumGrid = new FlowPane(10, 10);
    private final VBox composerBox = new VBox(8);
    private final TextArea composer = new TextArea();
    private final HBox draftsRow = new HBox(8);
    private final ComboBox<String> visibilityBox = new ComboBox<>();
    private final Button publishButton = new Button("发布");
    private final Button loadMore = new Button("加载更多");
    private final Label empty = new Label("还没有动态");
    private final Label statsLabel = new Label();
    private final ScrollPane feedScroll;
    private final ScrollPane albumScroll;
    private final List<Draft> drafts = new ArrayList<>();
    private Button timelineTab;
    private Button spaceTab;
    private Button albumTab;

    private long nextBeforeTs;
    private long albumBeforeTs;
    private boolean loading;
    private boolean albumMode;
    private String currentScope;   // null = 好友动态；否则某个 QC 号

    private record Draft(Path file, boolean video) {
    }

    private MomentSpaceView(Window owner, AppContext ctx, String qcId, String name, boolean self) {
        this(owner, ctx, qcId, name, self, false);
    }

    private MomentSpaceView(Window owner, AppContext ctx, String qcId, String name,
                            boolean self, boolean timeline) {
        this.ctx = ctx;
        this.spaceQcId = qcId;
        this.self = self;
        this.timeline = timeline;
        this.spaceName = name;
        this.currentScope = qcId;
        this.shell = new ModalShell(owner, timeline ? "QC 空间" : name + " 的 QC 空间", 940, 800);

        VBox content = new VBox(10);
        content.setPadding(new Insets(14));
        content.getStyleClass().add("modal-body");

        feedScroll = new ScrollPane(feed);
        feedScroll.setFitToWidth(true);
        feedScroll.getStyleClass().add("edge-to-edge");
        VBox.setVgrow(feedScroll, Priority.ALWAYS);

        albumScroll = new ScrollPane(albumGrid);
        albumScroll.setFitToWidth(true);
        albumScroll.getStyleClass().add("edge-to-edge");
        albumScroll.setVisible(false);
        albumScroll.setManaged(false);
        VBox.setVgrow(albumScroll, Priority.ALWAYS);

        statsLabel.getStyleClass().add("profile-sub");
        empty.getStyleClass().add("empty-state");
        empty.setGraphic(Icons.of("globe", 26));
        empty.setContentDisplay(javafx.scene.control.ContentDisplay.TOP);
        empty.setGraphicTextGap(8);
        empty.setPadding(new Insets(28, 0, 8, 0));
        empty.setMaxWidth(Double.MAX_VALUE);
        empty.setAlignment(Pos.CENTER);

        content.getChildren().addAll(buildHeader(name), buildTabs(), buildComposer(),
                statsLabel, feedScroll, albumScroll, loadMore);
        loadMore.getStyleClass().add("secondary-button");
        loadMore.setMaxWidth(Double.MAX_VALUE);
        loadMore.setOnAction(e -> {
            if (albumMode) {
                loadAlbum(false);
            } else {
                load(false);
            }
        });
        shell.setContent(content);
        updateTabStyles();
        updateHeader();
        shell.stage().setOnShown(e -> {
            load(true);
            loadStats();
        });
    }

    public static void show(Window owner, AppContext ctx, String qcId, String name, boolean self) {
        new MomentSpaceView(owner, ctx, qcId, name, self).shell.showAndWait();
    }

    /** 左侧导航「空间」：好友 + 自己的动态，按时间倒序 */
    public static void showTimeline(Window owner, AppContext ctx) {
        String name = ctx.me() == null ? "我" : ctx.me().displayName();
        new MomentSpaceView(owner, ctx, null, name, true, true).shell.showAndWait();
    }

    // ------------------------------------------------------------------
    // 头部 / 标签页
    // ------------------------------------------------------------------

    private Node buildHeader(String name) {
        Label avatar = new Label(Avatars.initial(name));
        avatar.getStyleClass().add("avatar");
        avatar.setMinSize(56, 56);
        avatar.setPrefSize(56, 56);
        avatar.setMaxSize(56, 56);
        if (spaceQcId != null) {
            Avatars.apply(avatar, spaceQcId, null);
        }
        Label title = new Label(timeline ? "QC 空间" : name + " 的 QC 空间");
        title.getStyleClass().add("profile-name");
        Label sub = new Label(timeline
                ? "好友和我的动态 · 按时间倒序"
                : "QC " + (spaceQcId == null ? "" : spaceQcId) + " · 动态 / 相册");
        sub.getStyleClass().add("profile-sub");
        headerTitle = title;
        headerSub = sub;
        VBox texts = new VBox(3, title, sub);
        HBox header = new HBox(12, avatar, texts);
        header.setAlignment(Pos.CENTER_LEFT);
        header.setPadding(new Insets(0, 0, 4, 0));
        return header;
    }

    private Node buildTabs() {
        timelineTab = tabButton("好友动态");
        timelineTab.setOnAction(e -> switchTab(null, false));

        spaceTab = tabButton(self ? "我的空间" : "TA 的空间");
        spaceTab.setOnAction(e -> {
            String target = spaceQcId != null ? spaceQcId
                    : (ctx.me() == null ? null : ctx.me().id());
            switchTab(target, false);
        });

        albumTab = tabButton("相册");
        albumTab.setOnAction(e -> switchTab(spaceQcId, true));

        HBox tabs = new HBox(8, timelineTab, spaceTab, albumTab);
        tabs.setAlignment(Pos.CENTER_LEFT);
        return tabs;
    }

    private Button tabButton(String text) {
        Button button = new Button(text);
        button.getStyleClass().add("moment-tab");
        return button;
    }

    /** 只更新选中态颜色，三个标签尺寸始终一致 */
    private void updateTabStyles() {
        if (timelineTab == null) {
            return;
        }
        timelineTab.getStyleClass().remove("moment-tab-active");
        spaceTab.getStyleClass().remove("moment-tab-active");
        albumTab.getStyleClass().remove("moment-tab-active");
        Button active;
        if (albumMode) {
            active = albumTab;
        } else if (currentScope == null) {
            active = timelineTab;
        } else {
            active = spaceTab;
        }
        active.getStyleClass().add("moment-tab-active");
        updateHeader();
    }

    /** 切标签时同步标题和说明文字 */
    private void updateHeader() {
        if (headerTitle == null) {
            return;
        }
        String scope = currentScope == null
                ? (ctx.me() == null ? null : ctx.me().id()) : currentScope;
        if (timeline && currentScope == null && !albumMode) {
            headerTitle.setText("QC 空间");
            headerSub.setText("好友和我的动态 · 按时间倒序");
        } else {
            headerTitle.setText(spaceName + " 的 QC 空间");
            headerSub.setText("QC " + (scope == null ? "" : scope)
                    + (albumMode ? " · 相册" : " · 动态 / 相册"));
        }
    }

    private void switchTab(String qcId, boolean album) {
        this.currentScope = qcId;
        this.albumMode = album;
        updateTabStyles();
        boolean showAlbum = album;
        feedScroll.setVisible(!showAlbum);
        feedScroll.setManaged(!showAlbum);
        albumScroll.setVisible(showAlbum);
        albumScroll.setManaged(showAlbum);
        composerBox.setVisible(!showAlbum && self);
        composerBox.setManaged(!showAlbum && self);
        if (showAlbum) {
            loadAlbum(true);
        } else {
            load(true);
        }
        loadStats();
    }

    // ------------------------------------------------------------------
    // 发布器
    // ------------------------------------------------------------------

    private Node buildComposer() {
        if (!self) {
            return new Label();
        }
        composer.setPromptText("分享这一刻…（最多 9 张图片，或 1 个视频）");
        composer.setPrefRowCount(2);
        composer.setWrapText(true);
        composer.getStyleClass().add("message-input");

        Button image = new Button("图片");
        image.getStyleClass().add("secondary-button");
        image.setOnAction(e -> pickImage());
        Button video = new Button("视频");
        video.getStyleClass().add("secondary-button");
        video.setOnAction(e -> pickVideo());

        visibilityBox.getItems().setAll(VISIBILITY.keySet());
        Popups.install(visibilityBox);
        visibilityBox.setValue("公开");
        visibilityBox.getStyleClass().add("search-field");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        publishButton.getStyleClass().add("primary-button");
        publishButton.setOnAction(e -> publish());

        HBox actions = new HBox(8, image, video, visibilityBox, spacer, publishButton);
        actions.setAlignment(Pos.CENTER_LEFT);
        draftsRow.setAlignment(Pos.CENTER_LEFT);
        draftsRow.setSpacing(8);
        composerBox.getChildren().setAll(composer, draftsRow, actions);
        composerBox.setPadding(new Insets(10));
        composerBox.getStyleClass().add("moment-card");
        return composerBox;
    }

    private String selectedVisibility() {
        return VISIBILITY.getOrDefault(visibilityBox.getValue(), "PUBLIC");
    }

    private void pickImage() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("选择图片（可多选）");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(
                "图片", "*.png", "*.jpg", "*.jpeg", "*.gif", "*.webp", "*.bmp"));
        List<File> files = chooser.showOpenMultipleDialog(shell.stage());
        if (files == null) {
            return;
        }
        for (File file : files) {
            if (drafts.size() >= 9) {
                toast("最多 9 张图片");
                break;
            }
            if (drafts.stream().anyMatch(Draft::video)) {
                toast("视频动态不能再加图片");
                break;
            }
            drafts.add(new Draft(file.toPath(), false));
        }
        refreshDrafts();
    }

    private void pickVideo() {
        if (!drafts.isEmpty()) {
            toast("一条动态只能带 1 个视频（请先清空已选内容）");
            return;
        }
        FileChooser chooser = new FileChooser();
        chooser.setTitle("选择视频");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(
                "视频（MP4 / MOV / M4V）", "*.mp4", "*.m4v", "*.mov"));
        File file = chooser.showOpenDialog(shell.stage());
        if (file != null) {
            drafts.add(new Draft(file.toPath(), true));
            refreshDrafts();
        }
    }

    private void refreshDrafts() {
        draftsRow.getChildren().clear();
        for (Draft draft : drafts) {
            StackPane tile = new StackPane();
            tile.setPrefSize(84, 84);
            tile.setMaxSize(84, 84);
            tile.getStyleClass().add("moment-draft");
            if (draft.video()) {
                Label icon = new Label("视频");
                icon.setStyle("-fx-font-size: 15px; -fx-font-weight: bold; -fx-text-fill: #2d7ff9;");
                Label name = new Label(draft.file().getFileName().toString());
                name.getStyleClass().add("conv-time");
                name.setMaxWidth(76);
                VBox box = new VBox(2, icon, name);
                box.setAlignment(Pos.CENTER);
                tile.getChildren().add(box);
            } else {
                ImageView preview = new ImageView(new Image(
                        draft.file().toUri().toString(), 84, 84, true, true));
                preview.setFitWidth(84);
                preview.setFitHeight(84);
                tile.getChildren().add(preview);
            }
            Button remove = new Button("✕");
            remove.getStyleClass().add("title-button");
            remove.setOnAction(e -> {
                drafts.remove(draft);
                refreshDrafts();
            });
            StackPane.setAlignment(remove, Pos.TOP_RIGHT);
            tile.getChildren().add(remove);
            draftsRow.getChildren().add(tile);
        }
        draftsRow.setVisible(!drafts.isEmpty());
        draftsRow.setManaged(!drafts.isEmpty());
    }

    private void publish() {
        String text = composer.getText() == null ? "" : composer.getText().trim();
        if (text.isEmpty() && drafts.isEmpty()) {
            toast("写点什么，或者配一张图片/视频");
            return;
        }
        List<Draft> selected = new ArrayList<>(drafts);
        publishButton.setDisable(true);
        publishButton.setText("上传中…");
        uploadAll(selected, new ArrayList<>(), 0, text, selectedVisibility());
    }

    private void uploadAll(List<Draft> selected, List<MomentMedia> uploaded, int index,
                           String text, String visibility) {
        if (index >= selected.size()) {
            ctx.publishMoment(text, uploaded, visibility).whenComplete((moment, error) ->
                    Platform.runLater(() -> {
                        publishButton.setDisable(false);
                        publishButton.setText("发布");
                        if (error != null) {
                            toastAbove("发布失败：" + ClientErrors.describe(error), false);
                            return;
                        }
                        drafts.clear();
                        refreshDrafts();
                        composer.clear();
                        if (!albumMode && (currentScope == null || currentScope.equals(spaceQcId))) {
                            feed.getChildren().add(0, card(moment));
                            hideEmpty();
                        }
                        loadStats();
                        toastAbove("动态已发布", true);
                    }));
            return;
        }
        Draft draft = selected.get(index);
        var future = draft.video() ? ctx.uploadMomentVideo(draft.file()) : ctx.uploadMomentImage(draft.file());
        future.whenComplete((media, error) -> Platform.runLater(() -> {
            if (error != null) {
                publishButton.setDisable(false);
                publishButton.setText("发布");
                toastAbove("上传失败：" + ClientErrors.describe(error), false);
                return;
            }
            uploaded.add(media);
            uploadAll(selected, uploaded, index + 1, text, visibility);
        }));
    }

    // ------------------------------------------------------------------
    // 列表 / 相册
    // ------------------------------------------------------------------

    private void load(boolean reset) {
        if (loading) {
            return;
        }
        loading = true;
        loadMore.setDisable(true);
        long before = reset ? 0 : nextBeforeTs;
        if (reset) {
            nextBeforeTs = 0;
        }
        ctx.loadMoments(currentScope, before, PAGE_SIZE).whenComplete((resp, error) ->
                Platform.runLater(() -> {
                    loading = false;
                    loadMore.setDisable(false);
                    if (error != null) {
                        toast("加载失败：" + ClientErrors.describe(error));
                        return;
                    }
                    if (reset) {
                        feed.getChildren().clear();
                    }
                    List<Moment> moments = resp.moments() == null ? List.of() : resp.moments();
                    for (Moment moment : moments) {
                        feed.getChildren().add(card(moment));
                    }
                    nextBeforeTs = resp.nextBeforeTs();
                    boolean hasMore = resp.hasMore();
                    loadMore.setVisible(hasMore);
                    loadMore.setManaged(hasMore);
                    if (feed.getChildren().isEmpty()) {
                        feed.getChildren().add(empty);
                        empty.setVisible(true);
                        empty.setManaged(true);
                    } else {
                        hideEmpty();
                    }
                }));
    }

    private void loadAlbum(boolean reset) {
        if (loading) {
            return;
        }
        loading = true;
        loadMore.setDisable(true);
        long before = reset ? 0 : albumBeforeTs;
        if (reset) {
            albumGrid.getChildren().clear();
            albumBeforeTs = 0;
        }
        // 去掉上一次的“空相册”占位
        albumGrid.getChildren().removeIf(node -> node instanceof Label);
        final String qcId = currentScope == null ? (ctx.me() == null ? null : ctx.me().id()) : currentScope;
        ctx.loadMomentMedia(qcId, before, 60).whenComplete((resp, error) -> Platform.runLater(() -> {
            loading = false;
            loadMore.setDisable(false);
            if (error != null) {
                toast("加载相册失败：" + ClientErrors.describe(error));
                return;
            }
            if (resp == null) {
                return;
            }
            for (var item : resp.items()) {
                albumGrid.getChildren().add(albumTile(item.media(), item.momentId()));
            }
            if (albumGrid.getChildren().isEmpty()) {
                Label emptyAlbum = new Label("相册还是空的");
                emptyAlbum.getStyleClass().add("empty-state");
                emptyAlbum.setGraphic(Icons.of("image", 26));
                emptyAlbum.setContentDisplay(javafx.scene.control.ContentDisplay.TOP);
                emptyAlbum.setGraphicTextGap(8);
                emptyAlbum.setMaxWidth(Double.MAX_VALUE);
                emptyAlbum.setAlignment(Pos.CENTER);
                albumGrid.getChildren().add(emptyAlbum);
            }
            albumBeforeTs = resp.nextBeforeTs();
            loadMore.setVisible(resp.hasMore());
            loadMore.setManaged(resp.hasMore());
        }));
    }

    private Node albumTile(MomentMedia media, String momentId) {
        String url = io.quack.client.net.MediaUrls.resolve(media.url());
        String cover = io.quack.client.net.MediaUrls.resolve(media.cover());
        String thumb = io.quack.client.net.MediaUrls.withWidth(media.isVideo() ? cover : url, 320);
        ImageView view = new ImageView(io.quack.client.net.ImageCache.load(thumb, 320, 320));
        view.setFitWidth(200);
        view.setFitHeight(200);
        view.setPreserveRatio(true);
        StackPane tile = new StackPane(view);
        tile.getStyleClass().add("moment-media");
        if (media.isVideo()) {
            Label play = new Label("▶");
            play.getStyleClass().add("video-play");
            Label duration = new Label(io.quack.client.media.VideoThumbnailer
                    .formatDuration(media.durationMs()));
            duration.getStyleClass().add("video-duration");
            StackPane.setAlignment(duration, Pos.BOTTOM_RIGHT);
            StackPane.setMargin(duration, new Insets(0, 8, 6, 0));
            tile.getChildren().addAll(play, duration);
            tile.setOnMouseClicked(e -> ctx.playVideo(url));
        } else {
            tile.setOnMouseClicked(e -> ImageViewerDialog.show(shell.stage(), url));
        }
        return tile;
    }

    private void hideEmpty() {
        feed.getChildren().remove(empty);
    }

    private void loadStats() {
        String qcId = currentScope == null ? (ctx.me() == null ? null : ctx.me().id()) : currentScope;
        ctx.loadMomentStats(qcId).whenComplete((stats, error) -> Platform.runLater(() -> {
            if (error != null || stats == null) {
                return;
            }
            statsLabel.setText("动态 " + stats.posts() + " · 获赞 " + stats.likes()
                    + " · 评论 " + stats.comments() + " · 转发 " + stats.shares()
                    + " · 图片 " + stats.photos() + " · 视频 " + stats.videos());
        }));
    }

    // ------------------------------------------------------------------
    // 单条动态
    // ------------------------------------------------------------------

    private Node card(Moment moment) {
        Label avatar = new Label(Avatars.initial(moment.authorName()));
        avatar.getStyleClass().add("avatar");
        avatar.setMinSize(40, 40);
        avatar.setPrefSize(40, 40);
        avatar.setMaxSize(40, 40);
        Avatars.apply(avatar, moment.authorId(), moment.authorAvatar());

        Label name = new Label(moment.authorName());
        name.getStyleClass().add("conv-title");
        Label time = new Label(TIME.format(Instant.ofEpochMilli(moment.ts()))
                + (moment.edited() ? " · 已编辑" : ""));
        time.getStyleClass().add("conv-time");
        VBox who = new VBox(2, name, time);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Label visibility = new Label(visibilityLabel(moment.visibility()));
        visibility.getStyleClass().add("conv-time");
        HBox header = new HBox(10, avatar, who, spacer, visibility);
        header.setAlignment(Pos.CENTER_LEFT);

        VBox body = new VBox(8, header);
        if (moment.text() != null && !moment.text().isBlank()) {
            Label text = new Label(moment.text());
            text.getStyleClass().add("moment-text");
            text.setWrapText(true);
            body.getChildren().add(text);
        }
        if (moment.hasShared()) {
            body.getChildren().add(sharedBlock(moment.shared()));
        }
        body.getChildren().add(mediaGrid(moment.mediaOrEmpty()));

        Button like = new Button((moment.likedByMe() ? "已赞 " : "赞 ") + moment.likes());
        like.getStyleClass().add("moment-action");
        if (moment.likedByMe()) {
            like.getStyleClass().add("moment-action-active");
        }
        Button comment = new Button("评论 " + moment.comments());
        comment.getStyleClass().add("moment-action");

        Region spacer2 = new Region();
        HBox.setHgrow(spacer2, Priority.ALWAYS);
        HBox actions = new HBox(8, like, comment, spacer2);
        VBox cardRoot = new VBox(10);

        boolean mine = ctx.store().me() != null
                && ctx.store().me().id().equals(moment.authorId());
        if (mine) {
            Button edit = new Button("编辑");
            edit.getStyleClass().add("title-button");
            edit.setOnAction(e -> editDialog(moment, cardRoot));
            Button delete = new Button("删除");
            delete.getStyleClass().add("title-button");
            delete.setOnAction(e -> ctx.deleteMoment(moment.id()).whenComplete((ignored, error) ->
                    Platform.runLater(() -> {
                        if (error != null) {
                            toast("删除失败：" + ClientErrors.describe(error));
                        } else {
                            feed.getChildren().remove(cardRoot);
                            loadStats();
                        }
                    })));
            actions.getChildren().addAll(edit, delete);
        } else {
            Button share = new Button("转发");
            share.getStyleClass().add("title-button");
            share.setOnAction(e -> shareDialog(moment));
            actions.getChildren().add(share);
        }

        VBox commentsBox = new VBox(6);
        commentsBox.setVisible(false);
        commentsBox.setManaged(false);

        final boolean[] likedState = {moment.likedByMe()};
        like.setOnAction(e -> ctx.likeMoment(moment.id(), !likedState[0])
                .whenComplete((updated, error) -> Platform.runLater(() -> {
                    if (error != null || updated == null) {
                        return;
                    }
                    likedState[0] = updated.likedByMe();
                    like.setText((updated.likedByMe() ? "已赞 " : "赞 ") + updated.likes());
                    like.getStyleClass().setAll("moment-action");
                    if (updated.likedByMe()) {
                        like.getStyleClass().add("moment-action-active");
                    }
                    comment.setText("评论 " + updated.comments());
                })));

        comment.setOnAction(e -> {
            boolean show = !commentsBox.isVisible();
            commentsBox.setVisible(show);
            commentsBox.setManaged(show);
            if (show) {
                loadComments(moment, commentsBox, comment);
            }
        });

        Label likesLabel = new Label(moment.likes() == 0 ? "" : moment.likes() + " 人赞");
        likesLabel.getStyleClass().add("conv-time");
        likesLabel.setOnMouseClicked(e -> likesDialog(moment));
        likesLabel.setCursor(javafx.scene.Cursor.HAND);

        cardRoot.getChildren().setAll(body, actions, likesLabel, commentsBox);
        cardRoot.getStyleClass().add("moment-card");
        cardRoot.setPadding(new Insets(12, 14, 10, 14));
        return cardRoot;
    }

    private String visibilityLabel(String visibility) {
        return switch (visibility == null ? "PUBLIC" : visibility) {
            case "PRIVATE" -> "仅自己可见";
            case "FRIENDS" -> "好友可见";
            default -> "";
        };
    }

    /** 转发原动态的嵌套卡片 */
    private Node sharedBlock(Moment source) {
        Label author = new Label("@" + source.authorName());
        author.getStyleClass().add("conv-title");
        VBox box = new VBox(6, author);
        if (source.text() != null && !source.text().isBlank()) {
            Label text = new Label(source.text());
            text.getStyleClass().add("moment-text");
            text.setWrapText(true);
            box.getChildren().add(text);
        }
        box.getChildren().add(mediaGrid(source.mediaOrEmpty()));
        box.setPadding(new Insets(10));
        box.getStyleClass().add("moment-card");
        return box;
    }

    private Node mediaGrid(List<MomentMedia> media) {
        if (media == null || media.isEmpty()) {
            return new Label();
        }
        if (media.get(0).isVideo()) {
            return videoCover(media.get(0));
        }
        FlowPane grid = new FlowPane(6, 6);
        for (MomentMedia item : media) {
            String url = io.quack.client.net.MediaUrls.resolve(item.url());
            String thumb = io.quack.client.net.MediaUrls.withWidth(url, 320);
            ImageView view = new ImageView(io.quack.client.net.ImageCache.load(thumb, 320, 320));
            view.setFitWidth(190);
            view.setFitHeight(190);
            view.setPreserveRatio(true);
            view.setSmooth(true);
            StackPane tile = new StackPane(view);
            tile.getStyleClass().add("moment-media");
            tile.setOnMouseClicked(e -> ImageViewerDialog.show(shell.stage(), url));
            grid.getChildren().add(tile);
        }
        return grid;
    }

    private Node videoCover(MomentMedia media) {
        String cover = io.quack.client.net.MediaUrls.resolve(media.cover());
        String url = io.quack.client.net.MediaUrls.resolve(media.url());
        ImageView view = new ImageView(io.quack.client.net.ImageCache.load(
                io.quack.client.net.MediaUrls.withWidth(cover, 480), 480, 320));
        view.setFitWidth(420);
        view.setFitHeight(280);
        view.setPreserveRatio(true);
        Label play = new Label("▶");
        play.getStyleClass().add("video-play");
        Label duration = new Label(io.quack.client.media.VideoThumbnailer
                .formatDuration(media.durationMs()));
        duration.getStyleClass().add("video-duration");
        StackPane tile = new StackPane(view, play, duration);
        StackPane.setAlignment(duration, Pos.BOTTOM_RIGHT);
        StackPane.setMargin(duration, new Insets(0, 8, 6, 0));
        tile.getStyleClass().add("moment-media");
        tile.setMaxSize(420, 280);
        tile.setOnMouseClicked(e -> ctx.playVideo(url));
        return tile;
    }

    // ------------------------------------------------------------------
    // 评论 / 回复
    // ------------------------------------------------------------------

    private void loadComments(Moment moment, VBox box, Button counter) {
        box.getChildren().clear();
        ctx.loadMomentComments(moment.id()).whenComplete((comments, error) -> Platform.runLater(() -> {
            if (error != null) {
                toast("加载评论失败：" + ClientErrors.describe(error));
                return;
            }
            renderComments(moment, box, counter, comments);
        }));
    }

    private void renderComments(Moment moment, VBox box, Button counter, List<MomentComment> comments) {
        box.getChildren().clear();
        counter.setText("评论 " + comments.size());
        for (MomentComment comment : comments) {
            boolean reply = comment.parentId() != null;
            Label author = new Label(reply
                    ? comment.authorName() + " 回复 " + comment.replyToName() + "："
                    : comment.authorName() + "：");
            author.getStyleClass().add("profile-sub");
            Label content = new Label(comment.text());
            content.setWrapText(true);
            HBox row = new HBox(4, author, content);
            row.setAlignment(Pos.TOP_LEFT);
            if (reply) {
                row.setPadding(new Insets(0, 0, 0, 18));
            }
            boolean canDelete = ctx.store().me() != null
                    && (ctx.store().me().id().equals(comment.authorId())
                    || ctx.store().me().id().equals(moment.authorId()));
            Region spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);
            Button replyButton = new Button("回复");
            replyButton.getStyleClass().add("title-button");
            replyButton.setOnAction(e -> replyDialog(moment, comment, box, counter));
            row.getChildren().addAll(spacer, replyButton);
            if (canDelete) {
                Button delete = new Button("删除");
                delete.getStyleClass().add("title-button");
                delete.setOnAction(e -> ctx.deleteMomentComment(comment.id())
                        .whenComplete((ignored, error) -> Platform.runLater(() -> {
                            if (error == null) {
                                loadComments(moment, box, counter);
                            }
                        })));
                row.getChildren().add(delete);
            }
            box.getChildren().add(row);
        }
        TextField input = new TextField();
        input.setPromptText("写评论…");
        input.getStyleClass().add("search-field");
        Button send = new Button("发送");
        send.getStyleClass().add("small-primary-button");
        Runnable submit = () -> {
            String text = input.getText() == null ? "" : input.getText().trim();
            if (text.isEmpty()) {
                return;
            }
            send.setDisable(true);
            ctx.addMomentComment(moment.id(), text).whenComplete((comment, error) ->
                    Platform.runLater(() -> {
                        send.setDisable(false);
                        if (error != null) {
                            toast("评论失败：" + ClientErrors.describe(error));
                        } else {
                            input.clear();
                            loadComments(moment, box, counter);
                        }
                    }));
        };
        send.setOnAction(e -> submit.run());
        input.setOnAction(e -> submit.run());
        HBox editor = new HBox(6, input, send);
        HBox.setHgrow(input, Priority.ALWAYS);
        box.getChildren().add(editor);
    }

    // ------------------------------------------------------------------
    // 弹窗：编辑 / 转发 / 点赞列表 / 回复
    // ------------------------------------------------------------------

    private void editDialog(Moment moment, VBox cardRoot) {
        ModalShell dialog = new ModalShell(shell.stage(), "编辑动态", 540, 420);
        TextArea area = new TextArea(moment.text());
        area.setWrapText(true);
        area.setPrefRowCount(5);
        ComboBox<String> visibility = new ComboBox<>();
        Popups.install(visibility);
        visibility.getItems().setAll(VISIBILITY.keySet());
        visibility.setValue(labelOf(moment.visibility()));
        Button save = new Button("保存");
        save.getStyleClass().add("primary-button");
        save.setOnAction(e -> {
            save.setDisable(true);
            ctx.editMoment(moment.id(), area.getText(), VISIBILITY.getOrDefault(
                    visibility.getValue(), "PUBLIC")).whenComplete((updated, error) ->
                    Platform.runLater(() -> {
                        save.setDisable(false);
                        if (error != null) {
                            toast("编辑失败：" + ClientErrors.describe(error));
                            return;
                        }
                        int index = feed.getChildren().indexOf(cardRoot);
                        if (index >= 0) {
                            feed.getChildren().set(index, card(updated));
                        }
                        dialog.close();
                        loadStats();
                        toastAbove("已保存", true);
                    }));
        });
        HBox actions = new HBox(8, visibility, save);
        actions.setAlignment(Pos.CENTER_LEFT);
        VBox body = new VBox(10, area, actions);
        body.setPadding(new Insets(12));
        body.getStyleClass().add("modal-body");
        dialog.setContent(body);
        dialog.showAndWait();
    }

    private void shareDialog(Moment moment) {
        ModalShell dialog = new ModalShell(shell.stage(), "转发动态", 540, 380);
        Label hint = new Label("转发 " + moment.authorName() + " 的动态");
        hint.getStyleClass().add("profile-sub");
        TextArea area = new TextArea();
        area.setPromptText("说点什么…（可留空）");
        area.setWrapText(true);
        area.setPrefRowCount(3);
        ComboBox<String> visibility = new ComboBox<>();
        Popups.install(visibility);
        visibility.getItems().setAll(VISIBILITY.keySet());
        visibility.setValue("公开");
        Button share = new Button("转发");
        share.getStyleClass().add("primary-button");
        share.setOnAction(e -> {
            share.setDisable(true);
            ctx.shareMoment(moment.id(), area.getText(), VISIBILITY.getOrDefault(
                    visibility.getValue(), "PUBLIC")).whenComplete((created, error) ->
                    Platform.runLater(() -> {
                        share.setDisable(false);
                        if (error != null) {
                            toast("转发失败：" + ClientErrors.describe(error));
                            return;
                        }
                        if (!albumMode && (currentScope == null || currentScope.equals(spaceQcId))) {
                            feed.getChildren().add(0, card(created));
                            hideEmpty();
                        }
                        dialog.close();
                        loadStats();
                        toastAbove("已转发", true);
                    }));
        });
        HBox actions = new HBox(8, visibility, share);
        actions.setAlignment(Pos.CENTER_LEFT);
        VBox body = new VBox(10, hint, area, actions);
        body.setPadding(new Insets(12));
        body.getStyleClass().add("modal-body");
        dialog.setContent(body);
        dialog.showAndWait();
    }

    private void likesDialog(Moment moment) {
        ModalShell dialog = new ModalShell(shell.stage(), "点赞列表", 360, 480);
        VBox list = new VBox(8);
        ScrollPane scroll = new ScrollPane(list);
        scroll.setFitToWidth(true);
        VBox body = new VBox(scroll);
        VBox.setVgrow(scroll, Priority.ALWAYS);
        body.setPadding(new Insets(12));
        body.getStyleClass().add("modal-body");
        dialog.setContent(body);
        ctx.loadMomentLikes(moment.id()).whenComplete((users, error) -> Platform.runLater(() -> {
            if (error != null) {
                list.getChildren().add(new Label("加载失败：" + ClientErrors.describe(error)));
                return;
            }
            if (users.isEmpty()) {
                list.getChildren().add(new Label("还没有人点赞"));
            }
            for (User user : users) {
                Label label = new Label(user.displayName() + "（" + user.id() + "）");
                label.getStyleClass().add("profile-sub");
                list.getChildren().add(label);
            }
        }));
        dialog.showAndWait();
    }

    private void replyDialog(Moment moment, MomentComment parent, VBox box, Button counter) {
        ModalShell dialog = new ModalShell(shell.stage(), "回复 " + parent.authorName(), 460, 240);
        TextField input = new TextField();
        input.setPromptText("回复 " + parent.authorName() + "…");
        input.getStyleClass().add("search-field");
        Button send = new Button("回复");
        send.getStyleClass().add("primary-button");
        send.setOnAction(e -> {
            String text = input.getText() == null ? "" : input.getText().trim();
            if (text.isEmpty()) {
                return;
            }
            send.setDisable(true);
            ctx.addMomentComment(moment.id(), text, parent.id()).whenComplete((comment, error) ->
                    Platform.runLater(() -> {
                        send.setDisable(false);
                        if (error != null) {
                            toast("回复失败：" + ClientErrors.describe(error));
                            return;
                        }
                        dialog.close();
                        loadComments(moment, box, counter);
                    }));
        });
        input.setOnAction(e -> send.fire());
        HBox row = new HBox(8, input, send);
        HBox.setHgrow(input, Priority.ALWAYS);
        VBox body = new VBox(10, row);
        body.setPadding(new Insets(12));
        body.getStyleClass().add("modal-body");
        dialog.setContent(body);
        dialog.showAndWait();
    }

    private String labelOf(String visibility) {
        return switch (visibility == null ? "PUBLIC" : visibility) {
            case "PRIVATE" -> "仅自己";
            case "FRIENDS" -> "好友";
            default -> "公开";
        };
    }

    private void toast(String message) {
        if (shell.stage().getScene() != null) {
            Toast.show(shell.stage().getScene().getRoot(), message);
        }
    }

    /** 发布结果提示：弹在动态窗口上方，停留 2 秒后渐隐 */
    private void toastAbove(String message, boolean success) {
        Toast.showAboveWindow(shell.stage(), message, success);
    }
}
