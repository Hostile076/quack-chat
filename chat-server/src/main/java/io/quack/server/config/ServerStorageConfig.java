package io.quack.server.config;

import io.quack.common.util.AppDirs;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 把 Tomcat 的工作区固定在应用自己的 data 目录。
 *
 * <p>为什么不用 {@code server.tomcat.basedir} 配置项：打包成 exe 后，
 * Spring Boot 在确定 basedir 之前就可能调用 {@code Files.createTempDirectory}，
 * 而临时目录是 JVM 启动时缓存的，运行期再改 {@code java.io.tmpdir} 已经无效 ——
 * 在临时目录不可写的机器上会直接闪退。
 *
 * <p>直接给工厂设 baseDirectory，Boot 就不会走“创建临时目录”那条分支，
 * 从根上避免这个问题。
 */
@Configuration
public class ServerStorageConfig {

    @Bean
    public WebServerFactoryCustomizer<TomcatServletWebServerFactory> tomcatBaseDirectoryCustomizer() {
        return factory -> factory.setBaseDirectory(AppDirs.subDir("tomcat").toFile());
    }
}
