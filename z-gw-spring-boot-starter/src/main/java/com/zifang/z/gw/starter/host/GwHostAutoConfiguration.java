package com.zifang.z.gw.starter.host;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

/**
 * 宿主侧胶水装配 (2026-10-03 自 z-opc main-starter 平移):
 * GwProxyController (/api/gw/** 管理面代理) + ZGwRoutesConfig (默认路由表装载).
 *
 * <p>跟随 z.gw.host.enabled 开关 (默认关): 寄生 all-in-one 模式由宿主打开,
 * standalone 分布式模式 (z-gw 独立容器) 不开.
 * ZGatewayAutoConfiguration (网关核心装配) 不受此开关影响, 始终可用.
 */
@Configuration
@ConditionalOnProperty(prefix = "z.gw.host", name = "enabled", havingValue = "true", matchIfMissing = false)
@ComponentScan(basePackages = "com.zifang.z.gw.starter.host")
public class GwHostAutoConfiguration {
}
