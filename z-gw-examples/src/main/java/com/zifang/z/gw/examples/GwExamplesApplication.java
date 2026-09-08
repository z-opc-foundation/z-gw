package com.zifang.z.gw.examples;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * z-gw 示例启动器 — 单进程内置网关 + admin API。
 *
 * <p>默认端口:
 * <ul>
 *   <li>{@code 8888} — Spring Boot Web (admin API)</li>
 *   <li>{@code 9090} — z-gw Netty (网关代理端口)</li>
 * </ul>
 *
 * <p>启动后:
 * <pre>
 *   curl http://localhost:8888/gw/admin/routes         # 查看路由
 *   curl http://localhost:8888/gw/admin/meta/status   # 网关状态
 *   curl http://localhost:9090/health                  # 网关健康检查
 *   curl http://localhost:8888/doc.html                # API 文档
 * </pre>
 */
@SpringBootApplication
public class GwExamplesApplication {

    public static void main(String[] args) {
        SpringApplication.run(GwExamplesApplication.class, args);
    }
}
