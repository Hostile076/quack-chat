import io.quack.client.AppContext;
import io.quack.client.ui.AppWindow;
import io.quack.client.ui.GroupAvatarDialog;
import io.quack.common.model.GroupDetail;
import io.quack.common.model.GroupMember;
import javafx.application.Platform;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** 群头像弹窗能打开 + 下载目录可写 */
public class GroupAvatarProbe {

    private static int failures;

    public static void main(String[] args) throws Exception {
        // 下载目录可写性
        java.io.File downloads = new java.io.File(System.getProperty("user.home", "."), "Downloads");
        boolean homeWritable = downloads.isDirectory() && canWrite(downloads);
        java.io.File appDir = io.quack.common.util.AppDirs.subDir("downloads").toFile();
        boolean appWritable = canWrite(appDir);
        System.out.printf("%s 用户下载目录可写=%s，应用下载目录(%s)可写=%s%n",
                (homeWritable || appWritable) ? "PASS" : "FAIL", homeWritable, appDir, appWritable);
        if (!homeWritable && !appWritable) {
            failures++;
        }

        CountDownLatch started = new CountDownLatch(1);
        Platform.startup(() -> {
            Platform.setImplicitExit(false);
            started.countDown();
        });
        started.await(10, TimeUnit.SECONDS);

        CountDownLatch done = new CountDownLatch(1);
        boolean[] opened = {false};
        Platform.runLater(() -> {
            Stage stage = new Stage();
            AppWindow window = new AppWindow(stage, "group-avatar-probe", 900, 700, true);
            AppContext ctx = new AppContext(window);
            window.show();

            List<GroupMember> members = new ArrayList<>();
            members.add(new GroupMember("10001", "Alice", "OWNER"));
            GroupDetail detail = new GroupDetail("q10001", "摸鱼小分队", "10001", "", 1, members);

            GroupAvatarDialog.show(stage, ctx, detail, updated -> {
            });
            after(700, () -> {
                for (Window window2 : new ArrayList<>(Window.getWindows())) {
                    if (window2 instanceof Stage dialog
                            && "更换群头像".equals(dialog.getTitle()) && dialog.isShowing()) {
                        opened[0] = true;
                        dialog.close();
                    }
                }
                window.stage().close();
                done.countDown();
            });
        });

        done.await(6, TimeUnit.SECONDS);
        if (!opened[0]) {
            failures++;
        }
        System.out.println((opened[0] ? "PASS" : "FAIL") + " 群头像弹窗能打开");
        System.out.println(failures == 0 ? "GROUP OK" : "GROUP FAILED: " + failures);
        Platform.exit();
        System.exit(failures == 0 ? 0 : 1);
    }

    private static boolean canWrite(java.io.File dir) {
        try {
            java.nio.file.Files.createDirectories(dir.toPath());
            java.io.File probe = java.io.File.createTempFile("quack-check", ".tmp", dir);
            return probe.delete();
        } catch (Exception error) {
            return false;
        }
    }

    private static void after(long millis, Runnable action) {
        new javafx.animation.Timeline(new javafx.animation.KeyFrame(
                javafx.util.Duration.millis(millis), e -> action.run())).play();
    }
}
