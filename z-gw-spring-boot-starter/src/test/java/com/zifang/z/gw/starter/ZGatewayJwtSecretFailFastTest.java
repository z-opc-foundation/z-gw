package com.zifang.z.gw.starter;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * starter 侧「缺 {@code zgw.security.jwt-secret} ⇒ 启动即失败」的守卫钉。
 *
 * <p><b>为什么核心仓那份守卫钉不够</b>：core 的
 * {@code GatewayPropertiesSecretDefaultGuardTest} 钉的是
 * {@code GatewayProperties#validate()} 这个<b>纯 Java 口径</b>；
 * 而真实使用 starter 的用户走的是 <b>Spring 装配路径</b> ——
 * {@code zgw.*} 先被 {@code ConfigurationPropertiesBindingPostProcessor} 绑定，
 * 再由 {@link ZGatewayProperties#afterPropertiesSet()} 回调触发校验。
 * <b>绑定 → 回调</b> 这一段接线一旦断掉（例如有人删了 {@code InitializingBean}，
 * 或把回调挪到了绑定之前），core 的守卫钉照样全绿，
 * 而 starter 用户会带着一个空密钥把网关起起来 ⇒ <b>恰好是本卡要掐死的那件事</b>。</p>
 *
 * <p><b>本守卫钉分两组，缺一不可</b>：</p>
 * <ol>
 *   <li><b>组 A · 接线隔离</b>（{@link #propsOnly()}）：上下文里<b>只有</b> properties bean，
 *       唯一的执行点是 {@code afterPropertiesSet()} ⇒ 这一组专门盯那根接线。
 *       <b>牙口自证</b>：把 {@code implements InitializingBean} 拆掉（或把回调挪到绑定之前），
 *       本组必须变红；</li>
 *   <li><b>组 B · 真实装配路径</b>（{@link #fullGateway()}）：整个
 *       {@link ZGatewayAutoConfiguration} 装上，钉真实用户看到的行为 ——
 *       缺密钥起不来、给了密钥起得来。</li>
 * </ol>
 *
 * <p><b>为什么必须拆成两组</b>：组 B 的失败其实有<b>两个</b>执行点 ——
 * {@code afterPropertiesSet()} 与 {@code GatewayBootstrap} 构造期校验 ——
 * 任一存在都会让组 B 变红 ⇒ 只跑组 B 时，拆掉其中一个执行点<b>照样绿</b>
 * （实测过：拆掉 {@code InitializingBean} 后组 B 仍绿，因为 bootstrap 那道校验兜住了）。
 * 组 A 把执行点收敛到一个，牙齿才咬得住。</p>
 *
 * <p><b>两条腿一起钉</b>：缺省必须炸（阳性），给了合法值必须过得去（阴性）——
 * 只钉阳性会把功能焊死，那不是硬化。</p>
 *
 * <p>组 B 的端口取 {@code zgw.server.port=0}（临时端口）—— 网关在上下文 refresh 后会真的起 Netty，
 * 用固定 9090 会撞掉本机在跑的实例；0 让内核挑临时端口，断言完随上下文关闭一起回收。</p>
 */
class ZGatewayJwtSecretFailFastTest {

    /** 组 A：只装 properties bean ⇒ 唯一执行点是 {@code afterPropertiesSet()}。 */
    private static ApplicationContextRunner propsOnly() {
        return new ApplicationContextRunner().withUserConfiguration(PropsOnlyConfig.class);
    }

    /** 组 B：整个 starter 自动装配 ⇒ 真实用户路径。 */
    private static ApplicationContextRunner fullGateway() {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(ZGatewayAutoConfiguration.class))
                .withPropertyValues("zgw.server.port=0");
    }

    /** 组 A 的配置类：只注册 {@link ZGatewayProperties}，不引入任何网关组件。 */
    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(ZGatewayProperties.class)
    static class PropsOnlyConfig {
    }

    // ==================================================================
    // 组 A · 接线隔离（牙齿对准 InitializingBean 那一根线）
    // ==================================================================

    @Test
    @DisplayName("接线隔离：不给 jwt-secret ⇒ properties bean 初始化必须失败")
    void missingSecretFailsPropertiesBean() {
        propsOnly().run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .hasRootCauseInstanceOf(IllegalStateException.class);
            assertThat(rootMessage(context.getStartupFailure()))
                    .contains("jwt-secret")
                    .contains("zgw.security.enabled=false");
        });
    }

    @Test
    @DisplayName("接线隔离：jwt-secret 给的是空白 ⇒ 同样必须失败（不得当成配了）")
    void blankSecretFailsPropertiesBean() {
        propsOnly().withPropertyValues("zgw.security.jwt-secret=   ").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .hasRootCauseInstanceOf(IllegalStateException.class);
        });
    }

    @Test
    @DisplayName("接线隔离：给了合法 jwt-secret ⇒ properties bean 必须初始化成功")
    void providedSecretInitializesPropertiesBean() {
        propsOnly().withPropertyValues("zgw.security.jwt-secret=unit-test-supplied-secret-value")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(ZGatewayProperties.class).getSecurity().getJwtSecret())
                            .isEqualTo("unit-test-supplied-secret-value");
                });
    }

    @Test
    @DisplayName("接线隔离：显式 zgw.security.enabled=false ⇒ 无密钥也能初始化（逃生口留着）")
    void disabledSecurityInitializesWithoutSecret() {
        propsOnly().withPropertyValues("zgw.security.enabled=false").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(ZGatewayProperties.class).getSecurity().isEnabled())
                    .isFalse();
        });
    }

    // ==================================================================
    // 组 B · 真实装配路径
    // ==================================================================

    @Test
    @DisplayName("真实路径：starter 用户不给 jwt-secret ⇒ 整个网关上下文必须起不来")
    void missingSecretBreaksGatewayStartup() {
        fullGateway().run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .hasRootCauseInstanceOf(IllegalStateException.class);
            assertThat(rootMessage(context.getStartupFailure())).contains("jwt-secret");
        });
    }

    @Test
    @DisplayName("真实路径：给了合法 jwt-secret ⇒ 整个网关上下文必须起得来（不是一律拒绝）")
    void providedSecretStartsGateway() {
        fullGateway().withPropertyValues("zgw.security.jwt-secret=unit-test-supplied-secret-value")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(ZGatewayProperties.class).getSecurity().getJwtSecret())
                            .isEqualTo("unit-test-supplied-secret-value");
                });
    }

    /** 取异常链最里层的消息 —— 包装层（BeanCreationException 等）会把根因消息藏起来。 */
    private static String rootMessage(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        return String.valueOf(cur.getMessage());
    }
}
