package io.quack.server;

import io.quack.common.util.AppDirs;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.nio.file.Path;

/**
 * 服务端入口。
 *
 * <p>启动后：
 * <ul>
 *   <li>WebSocket 端点：{@code ws://127.0.0.1:8080/ws}</li>
 *   <li>演示账号：alice / bob / carol，密码均为 123456</li>
 * </ul>
 */
@SpringBootApplication
@org.springframework.scheduling.annotation.EnableScheduling
public class ChatServerApplication {

    private static final Logger log = LoggerFactory.getLogger(ChatServerApplication.class);

    public static void main(String[] args) {
        prepareWritableDirs();
        SpringApplication.run(ChatServerApplication.class, args);
    }

    /**
     * 把临时目录与 Tomcat 工作区固定到应用自己的 data 目录。
     *
     * <p>为什么必须做：打包成 exe 后进程的 {@code java.io.tmpdir} 可能指向用户不可写的路径，
     * Tomcat 启动时创建临时工作区会抛 {@code AccessDeniedException}，表现为“双击闪退”。
     * 这里在 Spring 启动前把两个系统属性改掉（系统属性优先级高于 application.yml）。
     */
    private static void prepareWritableDirs() {
        Path dataDir = AppDirs.dataDir();
        Path tmp = AppDirs.subDir("tmp");
        Path tomcat = AppDirs.subDir("tomcat");

        System.setProperty("java.io.tmpdir", tmp.toString());
        System.setProperty("server.tomcat.basedir", tomcat.toString());

        log.info("数据目录: {}（临时文件与 Tomcat 工作区都在这里）", dataDir.toAbsolutePath());
    }
}
