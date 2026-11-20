package io.quack.client.media;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;
import javax.sound.sampled.TargetDataLine;
import java.util.Arrays;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * 语音通话的音频通道：采集麦克风 → 交给上层发送；收到对端 PCM → 扬声器播放。
 *
 * <p>格式统一为 <b>16kHz / 16bit / 单声道</b>，每帧 20ms（640 字节），
 * 在局域网里延迟大约几十毫秒。没有回声消除和降噪，戴耳机效果最好。
 */
public final class AudioCall implements AutoCloseable {

    public static final float SAMPLE_RATE = 16_000f;
    public static final int FRAME_BYTES = 640;

    private static final AudioFormat FORMAT =
            new AudioFormat(SAMPLE_RATE, 16, 1, true, false);

    private final Consumer<byte[]> onFrame;
    private final BlockingQueue<byte[]> playback = new LinkedBlockingQueue<>(64);

    private TargetDataLine microphone;
    private SourceDataLine speaker;
    private Thread captureThread;
    private Thread playThread;
    private volatile boolean running;
    private volatile boolean muted;

    public AudioCall(Consumer<byte[]> onFrame) {
        this.onFrame = onFrame;
    }

    /** @return null 表示成功，否则是给用户看的错误说明 */
    public String start() {
        try {
            DataLine.Info micInfo = new DataLine.Info(TargetDataLine.class, FORMAT);
            if (!AudioSystem.isLineSupported(micInfo)) {
                return "没有可用的麦克风";
            }
            microphone = (TargetDataLine) AudioSystem.getLine(micInfo);
            microphone.open(FORMAT);
            microphone.start();

            DataLine.Info speakerInfo = new DataLine.Info(SourceDataLine.class, FORMAT);
            if (AudioSystem.isLineSupported(speakerInfo)) {
                speaker = (SourceDataLine) AudioSystem.getLine(speakerInfo);
                speaker.open(FORMAT);
                speaker.start();
            }
        } catch (LineUnavailableException | IllegalArgumentException e) {
            close();
            return "麦克风不可用（可能被占用）：" + e.getMessage();
        }

        running = true;
        captureThread = new Thread(this::captureLoop, "quack-mic");
        captureThread.setDaemon(true);
        captureThread.start();

        playThread = new Thread(this::playLoop, "quack-speaker");
        playThread.setDaemon(true);
        playThread.start();
        return null;
    }

    private void captureLoop() {
        byte[] buffer = new byte[FRAME_BYTES];
        while (running) {
            int read = microphone.read(buffer, 0, buffer.length);
            if (read <= 0 || muted) {
                continue;
            }
            onFrame.accept(Arrays.copyOf(buffer, read));
        }
    }

    private void playLoop() {
        while (running) {
            try {
                byte[] frame = playback.poll(200, TimeUnit.MILLISECONDS);
                if (frame != null && speaker != null) {
                    speaker.write(frame, 0, frame.length);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /** 播放对端的一帧 PCM；队列满就丢弃，避免延迟越积越大 */
    public void play(byte[] frame) {
        if (frame == null || frame.length == 0) {
            return;
        }
        if (!playback.offer(frame)) {
            playback.poll();
            playback.offer(frame);
        }
    }

    public void setMuted(boolean value) {
        this.muted = value;
    }

    public boolean isMuted() {
        return muted;
    }

    public boolean hasSpeaker() {
        return speaker != null;
    }

    @Override
    public void close() {
        running = false;
        if (microphone != null) {
            microphone.stop();
            microphone.close();
            microphone = null;
        }
        if (speaker != null) {
            speaker.stop();
            speaker.close();
            speaker = null;
        }
        if (captureThread != null) {
            captureThread.interrupt();
            captureThread = null;
        }
        if (playThread != null) {
            playThread.interrupt();
            playThread = null;
        }
        playback.clear();
    }
}
