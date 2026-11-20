import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/** A 修改资料后，在线的 B 应收到 friend.updated 推送 */
public class FriendUpdateProbe {

    public static void main(String[] args) throws Exception {
        BlockingQueue<String> aIn = new LinkedBlockingQueue<>();
        BlockingQueue<String> bIn = new LinkedBlockingQueue<>();
        WebSocket a = connect(aIn);
        WebSocket b = connect(bIn);

        a.sendText(frame("login", 1, "{\"account\":\"10001\",\"password\":\"123456\"}"), true).join();
        b.sendText(frame("login", 1, "{\"account\":\"10002\",\"password\":\"123456\"}"), true).join();
        take(aIn, "login.resp");
        take(bIn, "login.resp");

        a.sendText(frame("user.update", 2,
                "{\"nickname\":\"Alice改名\",\"avatar\":\"/api/files/abcdef0123456789abcdef0123456789\"}"),
                true).join();
        take(aIn, "user.profile.resp");

        String pushed = take(bIn, "friend.updated");
        boolean ok = pushed != null && pushed.contains("Alice改名");
        System.out.println((ok ? "PASS" : "FAIL") + " 好友资料变更被推送给在线好友");
        System.out.println(ok ? "FRIENDUPDATE OK" : "FRIENDUPDATE FAILED");
        if (pushed != null) {
            System.out.println("payload=" + pushed);
        }
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
}
