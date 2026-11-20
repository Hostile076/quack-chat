package io.quack.client.ui;

import io.quack.client.AppContext;
import io.quack.client.ClientErrors;
import io.quack.common.model.CallSignal;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

/**
 * 通话界面。
 *
 * <p>只实现**信令**：呼叫、振铃、接听、拒绝、挂断。
 * 真正的音视频流需要 WebRTC，界面上会明确标注“仅信令”，不假装有画面。
 */
public final class CallDialog {

    private static CallDialog current;

    private final AppContext ctx;
    private final ModalShell shell;
    private final Label title = new Label();
    private final Label status = new Label();
    private final HBox actions = new HBox(10);
    /** 群通话里显示已接听者的头像 */
    private final javafx.scene.layout.FlowPane participantRow = new javafx.scene.layout.FlowPane(8, 8);

    private CallSignal signal;
    private boolean incoming;
    private boolean connected;
    private boolean groupCall;
    /** 已经主动挂断/拒绝过，避免窗口关闭时重复发一次 hangup */
    private boolean ended;

    private CallDialog(AppContext ctx, Window owner, boolean incoming) {
        this.ctx = ctx;
        this.incoming = incoming;
        this.shell = new ModalShell(owner, incoming ? "语音通话邀请" : "语音通话", 380, 300);

        title.getStyleClass().add("profile-name");
        title.setWrapText(true);
        status.getStyleClass().add("profile-sub");
        status.setWrapText(true);
        actions.setAlignment(Pos.CENTER);

        participantRow.setAlignment(Pos.CENTER);
        participantRow.setVisible(false);
        participantRow.setManaged(false);

        VBox content = new VBox(14, title, status, participantRow, actions);
        content.setAlignment(Pos.CENTER);
        content.setPadding(new Insets(24));
        content.getStyleClass().add("modal-body");
        shell.setContent(content);

        shell.stage().setOnHidden(e -> {
            // 点掉窗口的 X 也要挂断，否则服务端会一直认为这通电话还在进行
            if (connected && !ended && signal != null) {
                ended = true;
                ctx.hangupCall(signal.callId());
            }
            current = null;
            ctx.endAudio();
        });
    }

    public static void outgoing(Window owner, AppContext ctx, String toId, String toName) {
        outgoing(owner, ctx, toId, toName, false);
    }

    public static void outgoing(Window owner, AppContext ctx, String toId, String toName, boolean group) {
        CallDialog dialog = new CallDialog(ctx, owner, false);
        current = dialog;
        dialog.groupCall = group;
        dialog.title.setText(group ? "群通话 · " + toName : "正在呼叫 " + toName);
        dialog.status.setText(group ? "正在呼叫群成员…" : "等待对方接听…");
        if (group) {
            dialog.participantRow.setVisible(true);
            dialog.participantRow.setManaged(true);
        }
        Button cancel = new Button("取消");
        cancel.getStyleClass().add("secondary-button");
        cancel.setOnAction(e -> {
            if (dialog.signal != null) {
                ctx.hangupCall(dialog.signal.callId());
            }
            dialog.shell.close();
        });
        dialog.actions.getChildren().setAll(cancel);

        ctx.startCall(toId).whenComplete((signal, error) -> Platform.runLater(() -> {
            if (error != null) {
                // 失败原因要看得见，不能一闪而过
                dialog.title.setText("无法发起通话");
                dialog.status.setText(ClientErrors.describe(error));
                Button close = new Button("知道了");
                close.getStyleClass().add("secondary-button");
                close.setOnAction(e -> dialog.shell.close());
                dialog.actions.getChildren().setAll(close);
            } else {
                dialog.signal = signal;
            }
        }));
        dialog.shell.showAndWait();
    }

    public static void incoming(Window owner, AppContext ctx, CallSignal signal) {
        if (current != null) {
            // 已在通话/呼叫中，自动拒绝新来电
            ctx.rejectCall(signal.callId());
            return;
        }
        CallDialog dialog = new CallDialog(ctx, owner, true);
        current = dialog;
        dialog.signal = signal;
        dialog.title.setText(signal.fromName() + " 邀请你通话");
        dialog.status.setText("对方正在等待你的回应…");

        Button accept = new Button("接听");
        accept.getStyleClass().add("primary-button");
        accept.setOnAction(e -> {
            ctx.answerCall(signal.callId());
            dialog.startAudio();
        });

        Button reject = new Button("拒绝");
        reject.getStyleClass().add("secondary-button");
        reject.setOnAction(e -> {
            dialog.ended = true;
            ctx.rejectCall(signal.callId());
            dialog.shell.close();
        });

        dialog.actions.getChildren().setAll(accept, reject);
        dialog.shell.showAndWait();
    }

    /** 收到接听/拒绝/挂断事件 */
    public static void onEvent(CallSignal signal) {
        if (current == null || current.signal == null
                || !current.signal.callId().equals(signal.callId())) {
            return;
        }
        switch (signal.type()) {
            case CallSignal.ACCEPT -> current.startAudio();
            case CallSignal.REJECT -> {
                current.ctx.endAudio();
                Toast.show(current.actions, "对方拒绝了通话");
                current.shell.close();
            }
            default -> {
                current.ctx.endAudio();
                if (current.connected) {
                    Toast.show(current.actions, "通话已结束");
                }
                current.shell.close();
            }
        }
    }

    /** 接通后打开麦克风；失败也继续通话，只是没声音 */
    private void startAudio() {
        connected = true;
        String error = ctx.beginAudio(signal.callId());
        if (error == null) {
            status.setText("通话中 · 麦克风已开启");
            Button mute = new Button("静音");
            mute.getStyleClass().add("secondary-button");
            mute.setOnAction(e -> {
                boolean muted = ctx.toggleMute();
                mute.setText(muted ? "取消静音" : "静音");
                status.setText(muted ? "通话中 · 麦克风已静音" : "通话中 · 麦克风已开启");
            });
            actions.getChildren().setAll(mute, hangupButton());
        } else {
            status.setText("通话中 · " + error);
            actions.getChildren().setAll(hangupButton());
        }
    }

    /** 群通话参与者变化：把已接听的人头像摆出来 */
    public static void onParticipants(io.quack.common.dto.CallParticipants payload) {
        if (current == null || payload == null) {
            return;
        }
        if (current.signal != null && !current.signal.callId().equals(payload.callId())) {
            return;
        }
        current.participantRow.getChildren().clear();
        for (io.quack.common.dto.CallParticipant member : payload.members()) {
            Label avatar = new Label(Avatars.initial(member.name()));
            avatar.getStyleClass().add("avatar");
            avatar.setMinSize(44, 44);
            avatar.setPrefSize(44, 44);
            avatar.setMaxSize(44, 44);
            Avatars.apply(avatar, member.userId(), member.avatar());

            Label name = new Label(member.name());
            name.getStyleClass().add("member-name");
            name.setMaxWidth(64);
            name.setTextOverrun(javafx.scene.control.OverrunStyle.ELLIPSIS);

            VBox item = new VBox(4, avatar, name);
            item.setAlignment(Pos.CENTER);
            current.participantRow.getChildren().add(item);
        }
        if (payload.groupId() != null) {
            current.participantRow.setVisible(true);
            current.participantRow.setManaged(true);
            current.status.setText("群通话中 · 已接听 " + payload.members().size() + " 人");
        }
    }

    private Button hangupButton() {
        Button hangup = new Button("挂断");
        hangup.getStyleClass().add("danger-button");
        hangup.setOnAction(e -> {
            ended = true;
            if (signal != null) {
                ctx.hangupCall(signal.callId());
            }
            ctx.endAudio();
            shell.close();
        });
        return hangup;
    }
}
