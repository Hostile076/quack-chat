import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/** 已读回执：B 读了之后，A 应收到 msg.read.push */
public class ReadReceiptProbe {

    public static void main(String[] args) throws Exception {
        var a = new Client();
        var b = new Client();
        a.login("10001");
        b.login("10002");
        System.out.println("PASS 双方登录");

        a.ws.sendText(frame("msg.send", 2, "{\"clientMsgId\":\"r1\",\"to\":\"10002\","
                + "\"chatType\":\"PRIVATE\",\"msgType\":\"TEXT\",\"content\":\"在吗\"}"), true).join();
        String ack = a.take("msg.ack");
        System.out.println(ack != null ? "PASS 消息已发送" : "FAIL 消息发送失败");

        String push = b.take("msg.push");
        String ts = push == null ? null : number(push, "\"ts\":");
        System.out.println(push != null ? "PASS 对方收到消息 ts=" + ts : "FAIL 对方没收到");

        b.ws.sendText(frame("msg.read", 2, "{\"peerId\":\"10001\",\"chatType\":\"PRIVATE\","
                + "\"ts\":" + (ts == null ? "0" : ts) + "}"), true).join();

        String readPush = a.take("msg.read.push");
        boolean ok = readPush != null
                && readPush.replace("\\\"", "\"").contains("\"readerId\":\"10002\"");
        System.out.println((ok ? "PASS" : "FAIL") + " 发送方收到已读通知");
        System.out.println(ok ? "READ OK" : "READ FAILED");
        if (!ok && readPush != null) {
            System.out.println("push=" + readPush);
        }
        System.exit(ok ? 0 : 1);
    }

    private static String number(String frame, String prefix) {
        String normalized = frame.replace("\\\"", "\"");
        int start = normalized.indexOf(prefix);
        if (start < 0) {
            return "0";
        }
        start += prefix.length();
        int end = start;
        while (end < normalized.length() && Character.isDigit(normalized.charAt(end))) {
            end++;
        }
        return normalized.substring(start, end);
    }

    private static String frame(String cmd, long seq, String dataJson) {
        String escaped = dataJson.replace("\\", "\\\\").replace("\"", "\\\"");
        return "{\"cmd\":\"" + cmd + "\",\"seq\":" + seq + ",\"code\":0,\"data\":\"" + escaped + "\"}";
    }

    private static final class Client {
        BlockingQueue<String> inbox = new LinkedBlockingQueue<>();
        WebSocket ws;

        void login(String account) throws Exception {
            ws = HttpClient.newHttpClient().newWebSocketBuilder()
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
            ws.sendText(frame("login", 1, "{\"account\":\"" + account + "\",\"password\":\"123456\"}"), true).join();
            take("login.resp");
        }

        String take(String cmd) throws InterruptedException {
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
}
