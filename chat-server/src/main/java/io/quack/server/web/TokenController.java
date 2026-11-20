package io.quack.server.web;

import io.quack.common.model.User;
import io.quack.server.service.AuthService;
import io.quack.server.service.RateLimiter;
import io.quack.server.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.Optional;

/**
 * 用账号密码换一个 HTTP 上传令牌。
 *
 * <p>只发令牌，不建立/顶替 WebSocket 会话：客户端上传遇到 401（比如服务端重启后内存令牌失效）
 * 时调用它刷新 token，不会触发重新登录、也不会重建界面。
 */
@RestController
public class TokenController {

    public record TokenReq(String account, String password, String deviceId) {
    }

    private final UserService userService;
    private final AuthService authService;
    private final RateLimiter rateLimiter;

    public TokenController(UserService userService, AuthService authService, RateLimiter rateLimiter) {
        this.userService = userService;
        this.authService = authService;
        this.rateLimiter = rateLimiter;
    }

    @PostMapping("/api/token")
    public ResponseEntity<?> token(@RequestBody TokenReq req, HttpServletRequest http) {
        String ip = http.getRemoteAddr();
        if (rateLimiter.loginBlocked(ip)) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(Map.of("message", "登录失败次数过多，请稍后再试"));
        }
        Optional<User> authenticated = userService.authenticate(
                req == null ? null : req.account(), req == null ? null : req.password());
        if (authenticated.isEmpty()) {
            rateLimiter.recordLoginFailure(ip);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("message", "账号或密码错误"));
        }
        rateLimiter.clearLoginFailures(ip);
        String deviceId = req.deviceId() == null || req.deviceId().isBlank()
                ? "default" : req.deviceId().trim();
        String token = authService.issue(authenticated.get().id(), deviceId);
        return ResponseEntity.ok(Map.of("token", token));
    }
}
