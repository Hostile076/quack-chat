package io.quack.client.ui;

import io.quack.client.store.Conversation;
import javafx.application.Platform;
import io.quack.common.model.ChatMessage;
import io.quack.common.model.ChatType;
import io.quack.common.model.MsgType;
import javafx.beans.value.ObservableDoubleValue;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.awt.Desktop;
import java.net.URI;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * 消息气泡。
 *
 * <p><b>性能要点</b>：单元格会被反复复用，所以节点只在构造时创建一次，
 * {@link #updateItem} 里只改文本和可见性 —— 不再每次 update 都 new 一堆 Label/Binding。
 * 这是 JavaFX 自定义 ListCell 的官方推荐做法，也是滚动不卡的关键。
 */
public class MessageCell extends ListCell<ChatMessage> {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm")
            .withZone(ZoneId.systemDefault());
    /** 临时诊断：-Dquack.debug.cells=true 时把每个单元格的数据打到标准输出 */
    private static final boolean DEBUG_CELLS = Boolean.getBoolean("quack.debug.cells");

    private static final java.net.http.HttpClient DOWNLOAD_CLIENT = java.net.http.HttpClient.newHttpClient();
    private final Supplier<String> myIdSupplier;

    // 固定节点：整个生命周期只创建一次
    private final Label avatar = new Label();
    private final Label senderName = new Label();
    private final Label text = new Label();
    private final ImageView image = new ImageView();
    private final StackPane imageFrame = new StackPane(image);
    private final Label fileIcon = new Label();
    private final Label fileName = new Label();
    private final Label fileMeta = new Label();
    private final HBox fileCard = new HBox(10);
    private final javafx.scene.control.ProgressBar fileProgress = new javafx.scene.control.ProgressBar(0);
    private final Label fileProgressText = new Label();
    private final VBox fileTexts = new VBox(2);
    private final Label giftIcon = new Label("🎁");
    private final Label giftName = new Label();
    private final Label giftMeta = new Label();
    private final HBox giftCard = new HBox(10);
    /** 视频消息：封面 + 播放按钮 + 时长 */
    private final ImageView videoCover = new ImageView();
    private final Label videoPlay = new Label("▶");
    private final Label videoMeta = new Label();
    private final StackPane videoFrame = new StackPane();
    private final Label meta = new Label();
    private final StackPane contentStack;
    private final VBox body;
    private final HBox row;

    private String lastImageUrl;
    private String lastImageThumbUrl;
    private String lastVideoCover;
    private String lastVideoUrl;
    private boolean lastPending;
    private boolean lastFailed;
    private Boolean lastMine;

    private final java.util.function.Consumer<String> onOpenImage;
    private final java.util.function.Consumer<String> onOpenVideo;
    private final java.util.function.BooleanSupplier autoOpenDownload;
    private final java.util.function.Function<ChatMessage, String> readStatusSupplier;

    public MessageCell(Supplier<String> myIdSupplier, ObservableDoubleValue bubbleWidth,
                       Function<ChatMessage, ContextMenu> menuFactory,
                       java.util.function.Consumer<String> onOpenImage,
                       java.util.function.Consumer<String> onOpenVideo,
                       java.util.function.BooleanSupplier autoOpenDownload,
                       java.util.function.Function<ChatMessage, String> readStatusSupplier) {
        this.onOpenImage = onOpenImage;
        this.onOpenVideo = onOpenVideo;
        this.autoOpenDownload = autoOpenDownload;
        this.readStatusSupplier = readStatusSupplier;
        this.myIdSupplier = myIdSupplier;
        getStyleClass().add("message-cell");
        setPadding(Insets.EMPTY);

        // 文本
        text.setWrapText(true);
        text.maxWidthProperty().bind(bubbleWidth);

        // 图片：按原图比例展示，只缩小不放大；圆角用裁剪而不是描边
        image.setPreserveRatio(true);
        image.setSmooth(true);
        image.setFitWidth(220);
        image.setFitHeight(150);
        javafx.scene.shape.Rectangle imageClip = new javafx.scene.shape.Rectangle();
        imageClip.setArcWidth(24);
        imageClip.setArcHeight(24);
        imageClip.widthProperty().bind(image.fitWidthProperty());
        imageClip.heightProperty().bind(image.fitHeightProperty());
        image.setClip(imageClip);
        imageFrame.getStyleClass().add("image-bubble");
        imageFrame.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        imageFrame.setOnMouseClicked(e -> {
            if (onOpenImage != null && lastImageUrl != null && !lastImageUrl.isBlank()) {
                onOpenImage.accept(lastImageUrl);
            } else {
                openUrl(lastImageUrl);
            }
        });
        Tooltip.install(imageFrame, Popups.tooltip("点击查看大图"));

        // 文件
        fileCard.getStyleClass().add("file-card");
        fileCard.setAlignment(Pos.CENTER_LEFT);
        fileCard.setPadding(new Insets(9, 13, 9, 13));
        fileCard.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        fileCard.setMinHeight(44);
        fileProgress.setPrefWidth(150);
        fileProgress.setMaxWidth(Double.MAX_VALUE);
        fileProgress.setVisible(false);
        fileProgress.setManaged(false);
        fileProgress.getStyleClass().add("file-progress");
        fileProgressText.getStyleClass().add("file-progress-text");
        fileProgressText.setVisible(false);
        fileProgressText.setManaged(false);
        fileTexts.getChildren().addAll(fileName, fileMeta, fileProgress, fileProgressText);
        fileCard.getChildren().addAll(fileIcon, fileTexts);
        fileIcon.setGraphic(Icons.of("file", 24));
        fileIcon.getStyleClass().add("file-icon");
        fileIcon.setMinHeight(26);
        fileIcon.setMaxHeight(26);
        fileName.getStyleClass().add("file-name");
        fileMeta.getStyleClass().add("file-meta");

        // 礼物
        giftCard.getStyleClass().add("file-card");
        giftCard.setAlignment(Pos.CENTER_LEFT);
        giftCard.setPadding(new Insets(9, 13, 9, 13));
        giftCard.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        giftCard.setMinHeight(44);
        giftCard.getChildren().addAll(giftIcon, new VBox(2, giftName, giftMeta));
        giftIcon.setStyle("-fx-font-size: 26px;");
        giftIcon.setMinHeight(30);
        giftIcon.setMaxHeight(30);
        giftName.getStyleClass().add("file-name");
        giftMeta.getStyleClass().add("file-meta");

        videoCover.setPreserveRatio(true);
        videoCover.fitWidthProperty().bind(javafx.beans.binding.Bindings.multiply(bubbleWidth, 0.6));
        videoFrame.getStyleClass().add("video-bubble");
        videoFrame.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        videoFrame.setMinHeight(80);
        videoPlay.getStyleClass().add("video-play");
        videoMeta.getStyleClass().add("video-duration");
        StackPane.setAlignment(videoPlay, javafx.geometry.Pos.CENTER);
        StackPane.setAlignment(videoMeta, javafx.geometry.Pos.BOTTOM_RIGHT);
        StackPane.setMargin(videoMeta, new Insets(0, 8, 6, 0));
        videoFrame.getChildren().addAll(videoCover, videoPlay, videoMeta);
        videoFrame.setOnMouseClicked(e -> {
            if (lastVideoUrl != null && !lastVideoUrl.isBlank() && onOpenVideo != null) {
                onOpenVideo.accept(lastVideoUrl);
            }
        });

        contentStack = new StackPane(text, imageFrame, videoFrame, fileCard, giftCard);
        // 任何内容类型都不允许被压成一条线
        contentStack.setMinHeight(32);
        contentStack.setAlignment(Pos.CENTER_LEFT);

        senderName.getStyleClass().add("bubble-sender");
        senderName.setVisible(false);
        senderName.setManaged(false);

        meta.getStyleClass().add("bubble-meta");

        body = new VBox(3, senderName, contentStack, meta);
        body.setFillWidth(false);

        avatar.getStyleClass().addAll("avatar", "bubble-avatar");
        avatar.setMinSize(34, 34);
        avatar.setPrefSize(34, 34);
        avatar.setMaxSize(34, 34);

        row = new HBox(10, avatar, body);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(5, 22, 5, 18));

        setOnContextMenuRequested(event -> {
            ChatMessage message = getItem();
            if (message != null && menuFactory != null) {
                menuFactory.apply(message).show(this, event.getScreenX(), event.getScreenY());
                event.consume();
            }
        });
    }

    @Override
    protected void updateItem(ChatMessage message, boolean empty) {
        super.updateItem(message, empty);
        if (empty || message == null) {
            // 不能 setManaged(false)：ListCell 由 VirtualFlow 统一管理，
            // 取消 managed 会留下不更新也不回收的"幽灵单元格"（表现为残影气泡）。
            setGraphic(null);
            setText(null);
            return;
        }
        setGraphic(row);

        if (message.msgType() == MsgType.SYSTEM) {
            showOnly(text);
            text.getStyleClass().setAll("system-message");
            text.setText(message.content());
            avatar.setVisible(false);
            avatar.setManaged(false);
            senderName.setVisible(false);
            senderName.setManaged(false);
            meta.setVisible(false);
            meta.setManaged(false);
            row.setAlignment(Pos.CENTER);
            lastMine = null;
            return;
        }

        boolean mine = message.from() != null && message.from().equals(myIdSupplier.get());
        if (DEBUG_CELLS) {
            System.out.printf("[cell] id=%s type=%s len=%d from=%s chatType=%s content=%s%n",
                    message.id(), message.msgType(),
                    message.content() == null ? -1 : message.content().length(),
                    message.from(), message.chatType(), message.content());
        }
        applyBubbleStyle(mine);

        avatar.setVisible(!mine);
        avatar.setManaged(!mine);
        if (!mine) {
            avatar.setText(Avatars.initial(message.fromName()));
            if (!message.from().equals(avatar.getUserData())
                    || !java.util.Objects.equals(message.fromAvatar(), avatar.getProperties().get("avatarUrl"))) {
                avatar.setUserData(message.from());
                avatar.getProperties().put("avatarUrl", message.fromAvatar());
                Avatars.apply(avatar, message.from(), message.fromAvatar());
            }
        }

        boolean group = message.chatType() == ChatType.GROUP;
        senderName.setVisible(group && !mine);
        senderName.setManaged(group && !mine);
        if (group && !mine) {
            senderName.setText(message.fromName());
        }

        switch (message.msgType()) {
            case IMAGE -> {
                showOnly(imageFrame);
                String url = firstPart(message.content());
                lastImageUrl = url;
                String thumbUrl = io.quack.client.net.MediaUrls.withWidth(url, 640);
                if (!thumbUrl.equals(lastImageThumbUrl)) {
                    lastImageThumbUrl = thumbUrl;
                    imageFrame.getChildren().setAll(image);
                    Image loaded = io.quack.client.net.ImageCache.load(thumbUrl, 640, 640);
                    if (loaded == null) {
                        imageFrame.getChildren().setAll(new Label("图片加载失败 · 点击查看大图"));
                    } else {
                        loaded.errorProperty().addListener((obs, old, failed) -> {
                            if (Boolean.TRUE.equals(failed)) {
                                imageFrame.getChildren().setAll(
                                        new Label("图片加载失败 · 点击查看大图"));
                            }
                        });
                        // 加载完成后按原图尺寸限制显示：大图缩到 280×320 内，小图保持原始像素
                        loaded.progressProperty().addListener((obs, old, progress) -> {
                            if (progress.doubleValue() >= 1) {
                                applyImageSize(loaded);
                            }
                        });
                        if (loaded.getProgress() >= 1) {
                            applyImageSize(loaded);
                        }
                        image.setImage(loaded);
                    }
                }
            }
            case VIDEO -> {
                showOnly(videoFrame);
                String[] parts = message.content() == null ? new String[]{""} : message.content().split("\\|", -1);
                String url = parts.length > 0 ? parts[0] : "";
                String cover = parts.length > 1 && !parts[1].isBlank() ? parts[1] : url;
                long duration = 0;
                if (parts.length > 2) {
                    try {
                        duration = Long.parseLong(parts[2]);
                    } catch (NumberFormatException ignored) {
                        duration = 0;
                    }
                }
                String name = parts.length > 5 ? parts[5] : "视频";
                lastVideoUrl = url;
                String coverUrl = io.quack.client.net.MediaUrls.withWidth(cover, 480);
                if (!coverUrl.equals(lastVideoCover)) {
                    lastVideoCover = coverUrl;
                    Image loaded = io.quack.client.net.ImageCache.load(coverUrl, 480, 320);
                    if (loaded == null) {
                        videoCover.setImage(null);
                        videoMeta.setText("视频封面加载失败");
                    } else {
                        loaded.errorProperty().addListener((obs, old, failed) -> {
                            if (Boolean.TRUE.equals(failed)) {
                                videoCover.setImage(null);
                                videoMeta.setText("视频封面加载失败");
                            }
                        });
                        videoCover.setImage(loaded);
                    }
                }
                videoMeta.setText(io.quack.client.media.VideoThumbnailer.formatDuration(duration));
                Tooltip.install(videoFrame, Popups.tooltip("点击播放 " + name));
            }
            case FILE -> {
                showOnly(fileCard);
                String[] parts = message.content() == null ? new String[]{""} : message.content().split("\\|");
                String url = parts.length > 0 ? parts[0] : "";
                String name = parts.length > 1 ? parts[1] : "";
                long size = parts.length > 2 ? parseSize(parts[2]) : 0;
                String localPath = parts.length > 3 ? parts[3] : "";
                boolean mineFile = mine;

                fileName.setText(name == null || name.isBlank() ? "文件" : name);
                fileMeta.setText(humanSize(parts.length > 2 ? parts[2] : "")
                        + (mineFile && !localPath.isBlank() ? " · 点击打开" : " · 点击下载"));
                hideProgress();

                fileCard.setOnMouseClicked(e -> {
                    if (mineFile && !localPath.isBlank()) {
                        // 发送方：直接打开本地文件
                        openLocal(localPath);
                    } else {
                        startDownload(message.id(), url, name, size);
                    }
                });
            }
            case GIFT -> {
                showOnly(giftCard);
                String[] parts = message.content() == null ? new String[]{""} : message.content().split("\\|");
                String gift = parts.length > 1 ? parts[1] : "";
                giftName.setText(gift.isBlank() ? "礼物" : gift);
                String icon = parts.length > 2 ? parts[2] : "";
                giftIcon.setText(icon.isBlank() ? "🎁" : icon);
                giftMeta.setText("送出了一个礼物");
            }
            default -> {
                showOnly(text);
                text.setText(message.content());
            }
        }

        String messageId = message.id();
        boolean failed = messageId != null && messageId.startsWith(Conversation.FAILED_ID_PREFIX);
        boolean pending = !failed && messageId != null
                && messageId.startsWith(Conversation.LOCAL_ID_PREFIX);
        if (pending != lastPending || failed != lastFailed) {
            lastPending = pending;
            lastFailed = failed;
            meta.getStyleClass().setAll(failed ? "bubble-failed"
                    : pending ? "bubble-pending" : "bubble-meta");
        }
        meta.setVisible(true);
        meta.setManaged(true);
        String timeText = failed ? "发送失败"
                : pending ? "发送中…" : TIME.format(Instant.ofEpochMilli(message.ts()))
                        + (message.edited() ? " · 已编辑" : "");
        String readStatus = readStatusSupplier == null ? null : readStatusSupplier.apply(message);
        meta.setText(readStatus == null || readStatus.isBlank()
                ? timeText : timeText + " · " + readStatus);

        boolean rightAligned = mine;
        row.setAlignment(rightAligned ? Pos.CENTER_RIGHT : Pos.CENTER_LEFT);
    }

    /** 只显示一个内容节点，其它隐藏且不参与布局 */
    private void showOnly(Region active) {
        for (Region node : new Region[]{text, imageFrame, videoFrame, fileCard, giftCard}) {
            boolean on = node == active;
            node.setVisible(on);
            node.setManaged(on);
        }
    }

    private void applyBubbleStyle(boolean mine) {
        if (lastMine != null && lastMine == mine) {
            return;
        }
        lastMine = mine;
        text.getStyleClass().setAll(mine ? "bubble-mine" : "bubble-other");
        imageFrame.getStyleClass().setAll(mine ? "image-bubble-mine" : "image-bubble");
        videoFrame.getStyleClass().setAll(mine ? "video-bubble-mine" : "video-bubble");
        fileCard.getStyleClass().setAll(mine ? "file-card-mine" : "file-card");
        giftCard.getStyleClass().setAll(mine ? "file-card-mine" : "file-card");
        body.setAlignment(mine ? Pos.CENTER_RIGHT : Pos.CENTER_LEFT);
        meta.setAlignment(mine ? Pos.CENTER_RIGHT : Pos.CENTER_LEFT);
        senderName.setAlignment(mine ? Pos.CENTER_RIGHT : Pos.CENTER_LEFT);
    }

    private static String firstPart(String content) {
        if (content == null) {
            return "";
        }
        int index = content.indexOf('|');
        return index > 0 ? content.substring(0, index) : content;
    }

    /** 图片气泡按原图比例缩放（只缩小不放大）：小图保持原始像素，大图限制在 280x320 内 */
    private void applyImageSize(javafx.scene.image.Image loaded) {
        double width = loaded.getWidth();
        double height = loaded.getHeight();
        if (width <= 0 || height <= 0) {
            return;
        }
        double maxWidth = 280;
        double maxHeight = 320;
        double scale = Math.min(1, Math.min(maxWidth / width, maxHeight / height));
        image.setFitWidth(Math.max(48, Math.round(width * scale)));
        image.setFitHeight(Math.max(48, Math.round(height * scale)));
    }
    private static long parseSize(String raw) {
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private void hideProgress() {
        fileProgress.setVisible(false);
        fileProgress.setManaged(false);
        fileProgressText.setVisible(false);
        fileProgressText.setManaged(false);
    }

    private void showProgress(double progress, long done, long total) {
        fileProgress.setVisible(true);
        fileProgress.setManaged(true);
        fileProgress.setProgress(progress);
        fileProgressText.setVisible(true);
        fileProgressText.setManaged(true);
        fileProgressText.setText(humanSize(String.valueOf(done)) + " / " + humanSize(String.valueOf(total)));
    }

    /** 发送方直接打开本地文件 */
    private static void openLocal(String path) {
        try {
            java.io.File file = new java.io.File(path);
            if (!file.exists()) {
                return;
            }
            java.awt.Desktop.getDesktop().open(file);
        } catch (Exception ignored) {
            // 没有关联程序时静默失败，用户仍可另存
        }
    }

    /** 接收方：选路径 → 下载 → 文件下方显示进度 */
    private void startDownload(String messageId, String url, String name, long totalSize) {
        javafx.stage.Window owner = getScene() == null ? null : getScene().getWindow();
        javafx.stage.FileChooser chooser = new javafx.stage.FileChooser();
        chooser.setTitle("保存文件");
        chooser.setInitialFileName(name.isBlank() ? "download" : name);
        java.io.File defaultDir = defaultDownloadDir();
        if (defaultDir != null) {
            chooser.setInitialDirectory(defaultDir);
        }
        java.io.File target = chooser.showSaveDialog(owner);
        if (target == null) {
            return;
        }
        // 用户可能选到只读目录（例如受策略保护的 Downloads），直接换成可写目录
        if (!isWritable(target.getParentFile())) {
            java.io.File fallback = new java.io.File(defaultDownloadDir(),
                    target.getName() == null ? "download" : target.getName());
            fileMeta.setText("原路径无权限，改存到 " + fallback.getParent());
            target = fallback;
        }

        downloadTo(messageId, url, name, totalSize, target);
    }

    /** 真正的下载实现（抽出来便于测试）：进度写回文件卡片 */
    public void downloadTo(String messageId, String url, String name, long totalSize, java.io.File target) {
        showProgress(0, 0, totalSize);
        new Thread(() -> {
            try (java.io.InputStream in = DOWNLOAD_CLIENT.send(
                            java.net.http.HttpRequest.newBuilder(java.net.URI.create(url)).GET().build(),
                            java.net.http.HttpResponse.BodyHandlers.ofInputStream())
                    .body();
                 java.io.OutputStream out = java.nio.file.Files.newOutputStream(target.toPath())) {
                byte[] buffer = new byte[64 * 1024];
                long done = 0;
                int read;
                while ((read = in.read(buffer)) > 0) {
                    out.write(buffer, 0, read);
                    done += read;
                    long current = done;
                    Platform.runLater(() -> {
                        ChatMessage item = getItem();
                        if (item != null && messageId.equals(item.id())) {
                            double progress = totalSize > 0
                                    ? Math.min(1, current / (double) totalSize) : -1;
                            showProgress(progress, current, totalSize);
                        }
                    });
                }
                long finished = done;
                Platform.runLater(() -> {
                    ChatMessage item = getItem();
                    if (item != null && messageId.equals(item.id())) {
                        hideProgress();
                        fileMeta.setText(humanSize(String.valueOf(finished)) + " · 已下载");
                        if (autoOpenDownload != null && autoOpenDownload.getAsBoolean()) {
                            openLocal(target.getAbsolutePath());
                        }
                    }
                });
            } catch (java.nio.file.AccessDeniedException denied) {
                // 目标目录被策略保护：自动改存到应用数据目录
                java.io.File fallback = new java.io.File(defaultDownloadDir(),
                        target.getName() == null ? "download" : target.getName());
                Platform.runLater(() -> {
                    ChatMessage item = getItem();
                    if (item != null && messageId.equals(item.id())) {
                        fileMeta.setText("无权限，正在改存到 " + fallback.getParent());
                    }
                });
                try (java.io.InputStream in = DOWNLOAD_CLIENT.send(
                                java.net.http.HttpRequest.newBuilder(java.net.URI.create(url)).GET().build(),
                                java.net.http.HttpResponse.BodyHandlers.ofInputStream())
                        .body();
                     java.io.OutputStream out = java.nio.file.Files.newOutputStream(fallback.toPath())) {
                    in.transferTo(out);
                    long finished = fallback.length();
                    Platform.runLater(() -> {
                        ChatMessage item = getItem();
                        if (item != null && messageId.equals(item.id())) {
                            hideProgress();
                            fileMeta.setText(humanSize(String.valueOf(finished)) + " · 已保存到 " + fallback.getParent());
                        }
                    });
                } catch (Exception error) {
                    Platform.runLater(() -> {
                        ChatMessage item = getItem();
                        if (item != null && messageId.equals(item.id())) {
                            hideProgress();
                            fileMeta.setText("下载失败：" + error.getMessage());
                        }
                    });
                }
            } catch (Exception ex) {
                Platform.runLater(() -> {
                    ChatMessage item = getItem();
                    if (item != null && messageId.equals(item.id())) {
                        hideProgress();
                        fileMeta.setText("下载失败：" + ex.getMessage());
                    }
                });
            }
        }, "quack-download").start();
    }

    /**
     * 默认下载目录：按账号分开，避免多个账号的文件混在一起。
     * 优先 {@code 下载/QuackChat/<QC号>}，不可写则退到应用数据目录。
     */
    public java.io.File defaultDownloadDir() {
        String account = "unknown";
        try {
            String id = myIdSupplier == null ? null : myIdSupplier.get();
            if (id != null && !id.isBlank()) {
                account = id;
            }
        } catch (Exception ignored) {
            // 拿不到就落到 unknown
        }
        java.io.File downloads = new java.io.File(System.getProperty("user.home", "."), "Downloads");
        if (downloads.isDirectory()) {
            java.io.File perAccount = new java.io.File(new java.io.File(downloads, "QuackChat"), account);
            if (isWritable(perAccount)) {
                return perAccount;
            }
        }
        java.io.File dir = io.quack.common.util.AppDirs.subDir("downloads/" + account).toFile();
        return isWritable(dir) ? dir : null;
    }

    /** 真写一个临时文件来判断，比 canWrite() 可靠 */
    private static boolean isWritable(java.io.File dir) {
        if (dir == null) {
            return false;
        }
        try {
            java.nio.file.Files.createDirectories(dir.toPath());
            java.io.File probe = java.io.File.createTempFile("quack-write-check", ".tmp", dir);
            return probe.delete();
        } catch (Exception error) {
            return false;
        }
    }

    private static String humanSize(String raw) {
        try {
            long bytes = Long.parseLong(raw);
            if (bytes < 1024) {
                return bytes + " B";
            }
            if (bytes < 1024 * 1024) {
                return String.format("%.1f KB", bytes / 1024.0);
            }
            return String.format("%.1f MB", bytes / (1024.0 * 1024));
        } catch (NumberFormatException e) {
            return "";
        }
    }

    private static void openUrl(String url) {
        if (url == null || url.isBlank()) {
            return;
        }
        try {
            Desktop.getDesktop().browse(URI.create(url));
        } catch (Exception ignored) {
            // 没有默认浏览器就算了
        }
    }
}
