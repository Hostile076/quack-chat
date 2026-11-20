import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/** 好友备注：设置后应能在响应里读回，并且登录时好友列表用备注当显示名 */
public class RemarkProbe {

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
        String login = take(inbox, "login.resp");
        System.out.println(login != null && login.contains("login.resp") ? "PASS 登录" : "FAIL 登录");

        ws.sendText(frame("friend.remark", 2, "{\"friendId\":\"10002\",\"remark\":\"阿鲍\"}"), true).join();
        String remark = take(inbox, "friend.remark.resp");
        boolean setOk = remark != null && remark.replace("\\\"", "\"").contains("阿鲍");
        System.out.println((setOk ? "PASS" : "FAIL") + " 备注已保存");

        // 重新登录，好友列表里应显示备注
        ws.sendText(frame("login", 3, "{\"account\":\"10001\",\"password\":\"123456\"}"), true).join();
        String relogin = take(inbox, "login.resp");
        boolean shown = relogin != null && relogin.replace("\\\"", "\"").contains("阿鲍");
        System.out.println((shown ? "PASS" : "FAIL") + " 重新登录后好友列表显示备注");

        boolean ok = setOk && shown;
        System.out.println(ok ? "REMARK OK" : "REMARK FAILED");
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
