import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * 持久化验证：
 * write 阶段写消息/资料/备注 → 重启服务端 → read 阶段确认都还在。
 */
public class PersistProbe {

    private static final String MESSAGE = "persist-check-0731";
    private static final String SIGNATURE = "签名也要活过重启";
    private static final String REMARK = "持久化鲍";

    public static void main(String[] args) throws Exception {
        String mode = args.length == 0 ? "write" : args[0];
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
        if (login == null) {
            System.out.println("FAIL 登录失败（服务端没起来？）");
            System.exit(1);
        }

        if ("write".equals(mode)) {
            ws.sendText(frame("msg.send", 2, "{\"clientMsgId\":\"persist-1\",\"to\":\"10002\","
                    + "\"chatType\":\"PRIVATE\",\"msgType\":\"TEXT\",\"content\":\"" + MESSAGE + "\"}"),
                    true).join();
            String ack = take(inbox, "msg.ack");
            System.out.println((ack != null ? "PASS" : "FAIL") + " 写入消息");

            ws.sendText(frame("user.update", 3, "{\"signature\":\"" + SIGNATURE + "\"}"), true).join();
            String updated = take(inbox, "user.update.resp");
            System.out.println((updated != null ? "PASS" : "FAIL") + " 写入个性签名");

            ws.sendText(frame("friend.remark", 4, "{\"friendId\":\"10002\",\"remark\":\"" + REMARK + "\"}"),
                    true).join();
            String remark = take(inbox, "friend.remark.resp");
            System.out.println((remark != null ? "PASS" : "FAIL") + " 写入好友备注");

            System.out.println("写完了，等服务端落盘…");
            Thread.sleep(4000);
            System.out.println("WRITE DONE");
            System.exit(0);
        }

        // read 阶段
        boolean remarkOk = login.replace("\\\"", "\"").contains(REMARK);
        System.out.println((remarkOk ? "PASS" : "FAIL") + " 重启后好友备注还在");

        ws.sendText(frame("user.profile", 2, "{\"qcId\":\"10001\"}"), true).join();
        String profile = take(inbox, "user.profile.resp");
        boolean signatureOk = profile != null && profile.replace("\\\"", "\"").contains(SIGNATURE);
        System.out.println((signatureOk ? "PASS" : "FAIL") + " 重启后个性签名还在");

        ws.sendText(frame("history.req", 3, "{\"peerId\":\"10002\",\"chatType\":\"PRIVATE\","
                + "\"beforeTs\":0,\"limit\":50}"), true).join();
        String history = take(inbox, "history.resp");
        boolean messageOk = history != null && history.replace("\\\"", "\"").contains(MESSAGE);
        System.out.println((messageOk ? "PASS" : "FAIL") + " 重启后聊天记录还在");

        boolean ok = remarkOk && signatureOk && messageOk;
        System.out.println(ok ? "PERSIST OK" : "PERSIST FAILED");
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
