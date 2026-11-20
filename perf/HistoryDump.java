import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/** 打印与某个人的历史消息原始 JSON */
public class HistoryDump {

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

        ws.sendText("{\"cmd\":\"login\",\"seq\":1,\"code\":0,\"data\":\"{\\\"account\\\":\\\"10001\\\",\\\"password\\\":\\\"123456\\\"}\"}", true).join();
        System.out.println("login: " + take(inbox));

        String data = "{\\\"peerId\\\":\\\"10003\\\",\\\"chatType\\\":\\\"PRIVATE\\\",\\\"beforeTs\\\":0,\\\"limit\\\":50}";
        ws.sendText("{\"cmd\":\"history.req\",\"seq\":2,\"code\":0,\"data\":\"" + data + "\"}", true).join();
        String history = take(inbox);
        System.out.println("history: " + history);
        System.exit(0);
    }

    private static String take(BlockingQueue<String> inbox) throws InterruptedException {
        String frame = inbox.poll(8, TimeUnit.SECONDS);
        return frame == null ? "(timeout)" : frame;
    }
}
