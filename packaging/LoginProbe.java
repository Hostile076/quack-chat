import io.quack.client.net.WsClient;
import io.quack.common.dto.LoginReq;
import io.quack.common.dto.LoginResp;
import io.quack.common.protocol.Cmd;
import io.quack.common.protocol.Envelope;

import java.time.Duration;

/** 用真实客户端网络层验证：服务端起来后登录能通。 */
public class LoginProbe {
    public static void main(String[] args) throws Exception {
        WsClient ws = new WsClient();
        ws.connect("ws://127.0.0.1:8080/ws").get(10, java.util.concurrent.TimeUnit.SECONDS);
        Envelope resp = ws.request(Cmd.LOGIN, new LoginReq("alice", "123456"), Duration.ofSeconds(8)).get();
        System.out.println("code=" + resp.code() + " msg=" + resp.msg());
        LoginResp login = resp.dataAs(LoginResp.class);
        System.out.println("user=" + login.user().nickname()
                + " friends=" + login.friends().size()
                + " groups=" + login.groups().size());
        ws.close();
    }
}
