import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/** 改头像后重新拉资料，avatar 应该还在（自己主页要能显示） */
public class OwnProfileProbe {

    public static void main(String[] args) throws Exception {
        BlockingQueue<String> inbox = new LinkedBlockingQueue<>();
        WebSocket ws = HttpClient.newHttpClient().newWebSocketBuilder()
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

        ws.sendText(frame("login", 1, "{\"account\":\"10001\",\"password\":\"123456\"}"), true).join();
        take(inbox, "login.resp");

        String avatar = "/api/files/aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
        ws.sendText(frame("user.update", 2, "{\"avatar\":\"" + avatar + "\"}"), true).join();
        String updated = take(inbox, "user.update.resp");

        ws.sendText(frame("user.profile", 3, "{\"qcId\":\"10001\"}"), true).join();
        String profile = take(inbox, "user.profile.resp");

        boolean updateOk = updated != null && updated.replace("\\\"", "\"").contains(avatar);
        boolean profileOk = profile != null && profile.replace("\\\"", "\"").contains(avatar);
        System.out.println((updateOk ? "PASS" : "FAIL") + " user.update 返回了新头像");
        System.out.println((profileOk ? "PASS" : "FAIL") + " user.profile 里能读到新头像");
        boolean ok = updateOk && profileOk;
        System.out.println(ok ? "OWNPROFILE OK" : "OWNPROFILE FAILED");
        if (!ok && profile != null) {
            System.out.println("profile=" + profile);
        }
        System.exit(ok ? 0 : 1);
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
}
