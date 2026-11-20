import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/** 群通话：三个人都能收到呼叫、都能接听，参与者列表里应有 3 个人 */
public class GroupCallProbe {

    public static void main(String[] args) throws Exception {
        var a = new Client("10001");
        var b = new Client("10002");
        var c = new Client("10003");
        a.login();
        b.login();
        c.login();
        System.out.println("PASS 三个账号上线");

        a.ws.sendText(frame("call.invite", 2, "{\"toId\":\"q10001\"}"), true).join();
        String invited = a.take("call.answered");
        String callId = invited == null ? null : between(invited, "\"callId\":\"");
        System.out.println(callId == null ? "FAIL 发起群通话失败" : "PASS 发起群通话 callId=" + callId);

        String incomingB = b.take("call.incoming");
        String incomingC = c.take("call.incoming");
        System.out.println((incomingB != null && incomingC != null)
                ? "PASS 两个群成员都收到来电" : "FAIL 有成员没收到来电");

        b.ws.sendText(frame("call.accept", 2, "{\"callId\":\"" + callId + "\"}"), true).join();
        Thread.sleep(300);
        c.ws.sendText(frame("call.accept", 2, "{\"callId\":\"" + callId + "\"}"), true).join();

        // 会连续收到多次参与者更新，等人数涨到 3
        String participants = null;
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            String next = a.take("call.participants");
            if (next == null) {
                break;
            }
            participants = next;
            if (countMembers(next) >= 3) {
                break;
            }
        }
        boolean three = participants != null && countMembers(participants) >= 3;
        System.out.println((three ? "PASS" : "FAIL") + " 参与者列表人数 = "
                + (participants == null ? "null" : countMembers(participants)));

        a.ws.sendText(frame("call.hangup", 3, "{\"callId\":\"" + callId + "\"}"), true).join();
        String endedB = b.take("call.ended");
        String endedC = c.take("call.ended");
        System.out.println((endedB != null && endedC != null)
                ? "PASS 挂断后所有成员收到结束" : "FAIL 有成员没收到结束");

        boolean ok = callId != null && incomingB != null && incomingC != null && three
                && endedB != null && endedC != null;
        System.out.println(ok ? "GROUPCALL OK" : "GROUPCALL FAILED");
        System.exit(ok ? 0 : 1);
    }

    /** 粗略统计参与者条目数 */
    private static int countMembers(String frame) {
        String normalized = frame.replace("\\\"", "\"");
        int count = 0;
        int index = 0;
        while ((index = normalized.indexOf("\"userId\":\"", index)) >= 0) {
            count++;
            index += 10;
        }
        return count;
    }

    private static String between(String frame, String prefix) {
        String normalized = frame.replace("\\\"", "\"");
        int start = normalized.indexOf(prefix);
        if (start < 0) {
            return null;
        }
        start += prefix.length();
        int end = normalized.indexOf('"', start);
        return end < 0 ? null : normalized.substring(start, end);
    }

    private static String frame(String cmd, long seq, String dataJson) {
        String escaped = dataJson.replace("\\", "\\\\").replace("\"", "\\\"");
        return "{\"cmd\":\"" + cmd + "\",\"seq\":" + seq + ",\"code\":0,\"data\":\"" + escaped + "\"}";
    }

    private static final class Client {
        final String account;
        final BlockingQueue<String> inbox = new LinkedBlockingQueue<>();
        WebSocket ws;

        Client(String account) {
            this.account = account;
        }

        void login() throws Exception {
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
            ws.sendText(frame("login", 1,
                    "{\"account\":\"" + account + "\",\"password\":\"123456\"}"), true).join();
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
