import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/** 群主保存群头像后，group.detail 应该带上新的 avatar */
public class GroupAvatarSaveProbe {

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

        String avatar = "/api/files/0123456789abcdef0123456789abcdef";
        ws.sendText(frame("group.update", 2,
                "{\"groupId\":\"q10001\",\"avatar\":\"" + avatar + "\"}"), true).join();
        String update = take(inbox, "group.update.resp");
        boolean saved = update != null && update.replace("\\\"", "\"").contains(avatar);

        ws.sendText(frame("group.detail", 3, "{\"groupId\":\"q10001\"}"), true).join();
        String detail = take(inbox, "group.detail.resp");
        boolean persisted = detail != null && detail.replace("\\\"", "\"").contains(avatar);

        System.out.println((saved ? "PASS" : "FAIL") + " group.update 返回了新群头像");
        System.out.println((persisted ? "PASS" : "FAIL") + " group.detail 里能读到新群头像");
        boolean ok = saved && persisted;
        System.out.println(ok ? "GROUPAVATAR OK" : "GROUPAVATAR FAILED");
        if (!ok && update != null) {
            System.out.println("update=" + update);
        }
        if (!ok && detail != null) {
            System.out.println("detail=" + detail);
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
