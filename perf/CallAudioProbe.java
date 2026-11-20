import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.Base64;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/** 通话音频中继测试：A、B 建立通话后，A 发 PCM 帧，B 应收到同样的帧 */
public class CallAudioProbe {

    private static final BlockingQueue<String> A_IN = new LinkedBlockingQueue<>();
    private static final BlockingQueue<String> B_IN = new LinkedBlockingQueue<>();

    public static void main(String[] args) throws Exception {
        WebSocket a = connect(A_IN);
        WebSocket b = connect(B_IN);

        a.sendText(frame("login", 1, "{\"account\":\"10001\",\"password\":\"123456\"}"), true).join();
        b.sendText(frame("login", 1, "{\"account\":\"10002\",\"password\":\"123456\"}"), true).join();
        take(A_IN, "login.resp");
        take(B_IN, "login.resp");
        System.out.println("PASS 双方登录");

        a.sendText(frame("call.invite", 2, "{\"toId\":\"10002\"}"), true).join();
        String ack = take(A_IN, "call.answered");
        String incoming = take(B_IN, "call.incoming");
        String callId = extract(incoming, "callId");
        System.out.println(callId == null || callId.isBlank() ? "FAIL 未拿到 callId" : "PASS 呼叫建立");

        b.sendText(frame("call.accept", 2, "{\"callId\":\"" + callId + "\"}"), true).join();
        take(A_IN, "call.answered");
        System.out.println("PASS 对方接听");

        // 发送两帧 640 字节的 PCM
        byte[] pcm = new byte[640];
        for (int i = 0; i < pcm.length; i++) {
            pcm[i] = (byte) (i % 64);
        }
        String encoded = Base64.getEncoder().encodeToString(pcm);
        a.sendText(frame("call.audio", 3,
                "{\"callId\":\"" + callId + "\",\"data\":\"" + encoded + "\"}"), true).join();

        String relayed = take(B_IN, "call.audio.push");
        boolean ok = relayed != null && relayed.contains(encoded.substring(0, 40));
        System.out.println((ok ? "PASS" : "FAIL") + " 音频帧被中继到对端");
        System.out.println(ok ? "CALLAUDIO OK" : "CALLAUDIO FAILED");
        System.exit(ok ? 0 : 1);
    }

    private static WebSocket connect(BlockingQueue<String> inbox) throws Exception {
        return HttpClient.newHttpClient().newWebSocketBuilder()
                .buildAsync(URI.create("ws://127.0.0.1:8080/ws"), new WebSocket.Listener() {
                    private final StringBuilder buffer = new StringBuilder();

                    @Override
                    public void onOpen(WebSocket webSocket) {
                        webSocket.request(1);
                    }

                    @Override
                    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                        buffer.append(data);
                        if (last) {
                            inbox.add(buffer.toString());
                            buffer.setLength(0);
                        }
                        webSocket.request(1);
                        return null;
                    }
                }).get(10, TimeUnit.SECONDS);
    }

    private static String frame(String cmd, long seq, String dataJson) {
        String escaped = dataJson.replace("\\", "\\\\").replace("\"", "\\\"");
        return "{\"cmd\":\"" + cmd + "\",\"seq\":" + seq + ",\"code\":0,\"data\":\"" + escaped + "\"}";
    }

    private static String take(BlockingQueue<String> inbox, String cmd) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 8000;
        while (System.currentTimeMillis() < deadline) {
            String frame = inbox.poll(200, TimeUnit.MILLISECONDS);
            if (frame == null) {
                continue;
            }
            if (frame.contains("\"cmd\":\"" + cmd + "\"")) {
                return frame;
            }
        }
        return null;
    }

    private static String extract(String frame, String field) {
        if (frame == null) {
            return null;
        }
        String normalized = frame.replace("\\\"", "\"");
        int index = normalized.indexOf("\"" + field + "\":\"");
        if (index < 0) {
            return null;
        }
        int start = index + field.length() + 4;
        int end = normalized.indexOf('"', start);
        return end < 0 ? null : normalized.substring(start, end);
    }
}
