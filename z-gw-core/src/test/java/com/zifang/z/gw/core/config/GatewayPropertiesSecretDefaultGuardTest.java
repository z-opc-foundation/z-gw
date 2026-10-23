package com.zifang.z.gw.core.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * {@code GatewayProperties.jwtSecret} 的「不许有默认值」守卫钉。
 *
 * <p><b>为什么要有这一层</b>：本仓曾给 {@code jwtSecret} 内置过一个"请在生产环境修改"的
 * 占位串（长度 47）。它随 1.0.4/1.0.5/1.0.6 三个中央版本流出且<b>不可撤回</b>，
 * 于是它永久留在中央在架版本里 —— 从那一刻起，真正的风险就不是"这个值本身是不是真凭据"，
 * 而是<b>"将来有人把它当可用默认密钥直接用"</b>：拷进新项目、抄进示例、当临时密钥上线。
 * 留着默认值 = 一直邀请这件事发生。所以本仓现在<b>没有</b>默认值，
 * 缺省时<b>启动即失败</b>（{@link GatewayProperties#validate()}）。</p>
 *
 * <p><b>本守卫钉住两件事</b>：</p>
 * <ol>
 *   <li><b>不许回退</b>：{@code GatewayProperties}（含其内部类 {@code SecurityConfig}）里
 *       不得再出现<b>长度 ≥ 8</b> 的默认字符串常量（字段初值或静态常量都算）；
 *       源码里也不许再出现 {@code private String x = "...."} 这种带初值的写法；</li>
 *   <li><b>不许变成"一律拒绝"</b>：提供了合法值就必须在启动校验里通过 ——
 *       否则"缺省就炸"很容易被写成"怎么配都炸"，那不是硬化，是把功能焊死。</li>
 * </ol>
 *
 * <p><b>口径说明（为什么阈值是 8 而不是别的数）</b>：8 是"看起来像一句人话/一个词"的下限 ——
 * 比它短的串（{@code "0"}、{@code "id"}、{@code "utf8"} 之类）不可能是密钥，
 * 拿它当阈值只会误伤正常的短配置值；而 ≥ 8 的字面量才有"被人当成密钥抄走"的可能。
 * 硬编码的占位串历史上正是 47 字符这种"整句英文提示"的形态。</p>
 */
class GatewayPropertiesSecretDefaultGuardTest {

    /** 默认字符串常量的长度下限 —— 见类注释「口径说明」。 */
    private static final int MIN_DEFAULT_SECRET_LENGTH = 8;

    /** 源码里带字符串初值的字段声明（任何可见性、任何 static/final 组合）。 */
    private static final Pattern FIELD_WITH_STRING_INIT = Pattern.compile(
            "(?m)^\\s*(?:public|protected|private)?\\s*(?:static\\s+)?(?:final\\s+)?String\\s+(\\w+)\\s*=\\s*\"([^\"]*)\"");

    // ==================================================================
    // 守卫钉一：不得再有长度 ≥ 8 的默认字符串常量
    // ==================================================================

    @Test
    @DisplayName("守卫钉：GatewayProperties 不得再有长度 ≥ 8 的默认字符串常量")
    void noDefaultStringConstantOfSecretLength() {
        List<String> offenders = new ArrayList<String>();

        for (Class<?> clazz : new Class<?>[] {GatewayProperties.class, GatewayProperties.SecurityConfig.class}) {
            for (Field f : clazz.getDeclaredFields()) {
                if (f.getType() != String.class) {
                    continue;
                }
                String value = readFieldValue(clazz, f);
                if (value != null && value.length() >= MIN_DEFAULT_SECRET_LENGTH) {
                    offenders.add(clazz.getSimpleName() + "." + f.getName()
                            + "（长度 " + value.length() + "）");
                }
            }
        }

        if (!offenders.isEmpty()) {
            fail("GatewayProperties 里又出现了长度 ≥ " + MIN_DEFAULT_SECRET_LENGTH
                    + " 的默认字符串常量（这类字面量会被后来的人当可用密钥抄走）：" + offenders);
        }
    }

    @Test
    @DisplayName("守卫钉：源码里不得再有带字符串初值的字段声明")
    void noStringFieldWithInitializerInSource() {
        String src = readText(gatewayPropertiesSource());
        Matcher m = FIELD_WITH_STRING_INIT.matcher(src);
        List<String> offenders = new ArrayList<String>();
        while (m.find()) {
            String literal = m.group(2);
            if (literal.length() >= MIN_DEFAULT_SECRET_LENGTH) {
                offenders.add(m.group(1) + "（长度 " + literal.length() + "）");
            }
        }
        if (!offenders.isEmpty()) {
            fail("GatewayProperties.java 里又出现了带字符串初值的字段："
                    + offenders + " —— 默认值必须由外部显式提供");
        }
    }

    @Test
    @DisplayName("守卫钉：jwtSecret 缺省必须是 null（不是某个占位串）")
    void jwtSecretHasNoDefaultValue() {
        assertNull(new GatewayProperties().getSecurity().getJwtSecret(),
                "jwtSecret 缺省值必须是 null（没有默认值），不是某个占位串");
        assertNull(new GatewayProperties.SecurityConfig().getJwtSecret(),
                "SecurityConfig.jwtSecret 缺省值必须是 null");
    }

    // ==================================================================
    // 守卫钉二：缺省炸 ≠ 一律拒绝
    // ==================================================================

    @Test
    @DisplayName("阳性对照：安全开着且没给密钥 ⇒ 启动校验必须失败")
    void missingSecretFailsFast() {
        GatewayProperties props = new GatewayProperties();
        // 缺省：security.enabled = true 且 jwtSecret = null
        assertTrue(props.getSecurity().isEnabled(), "前提不成立：security 默认应当是开着的");
        IllegalStateException e = assertThrows(IllegalStateException.class, props::validate,
                "缺密钥却通过了启动校验 —— 那等于默许了一个空密钥");
        String msg = String.valueOf(e.getMessage());
        assertTrue(msg.contains("jwt-secret"), "异常消息没点名配置键：" + msg);
        assertTrue(msg.contains("zgw.security.enabled=false"),
                "异常消息没给出合法出路（显式关掉安全开关）：" + msg);
    }

    @Test
    @DisplayName("阴性对照：给了合法密钥 ⇒ 启动校验必须通过（不是一律拒绝）")
    void providedSecretPassesValidation() {
        GatewayProperties props = new GatewayProperties();
        props.getSecurity().setJwtSecret("unit-test-supplied-secret-value");
        assertDoesNotThrow(props::validate, "提供了合法密钥却被拒 —— 那是把功能焊死，不是硬化");
    }

    @Test
    @DisplayName("边界：显式关掉安全开关 ⇒ 不需要密钥也能过（明说不用 JWT ≠ 忘了配）")
    void disabledSecurityNeedsNoSecret() {
        GatewayProperties props = new GatewayProperties();
        props.getSecurity().setEnabled(false);
        assertDoesNotThrow(props::validate, "显式 zgw.security.enabled=false 仍被拒 —— 逃生口没了");
        assertEquals(false, props.getSecurity().isEnabled(), "前提不成立：安全开关应当是关着的");
    }

    // ==================================================================
    // 基础设施
    // ==================================================================

    /** 读一个 String 字段的"当前值"：实例字段读一份新实例，静态字段直接读。 */
    private static String readFieldValue(Class<?> clazz, Field f) {
        try {
            f.setAccessible(true);
            if (Modifier.isStatic(f.getModifiers())) {
                return (String) f.get(null);
            }
            Object instance = newInstanceOf(clazz);
            return (String) f.get(instance);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("读不到字段 " + clazz.getSimpleName() + "." + f.getName(), e);
        }
    }

    /** 造一个实例：外部类走公开无参构造，内部类走宿主类的无参构造。 */
    private static Object newInstanceOf(Class<?> clazz) {
        try {
            java.lang.reflect.Constructor<?> ctor = clazz.getDeclaredConstructor();
            ctor.setAccessible(true);
            return ctor.newInstance();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("造不出 " + clazz.getSimpleName() + " 的实例", e);
        }
    }

    /** GatewayProperties.java 的绝对路径：从仓根往上找。 */
    private static Path gatewayPropertiesSource() {
        Path p = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
        for (int i = 0; i < 10 && p != null; i++, p = p.getParent()) {
            Path f = p.resolve("z-gw-core/src/main/java/com/zifang/z/gw/core/config/GatewayProperties.java");
            if (Files.isRegularFile(f)) {
                return f;
            }
        }
        throw new IllegalStateException("未找到 GatewayProperties.java：从 " + Paths.get("").toAbsolutePath()
                + " 向上找不到 z-gw-core/src/main/java/com/zifang/z/gw/core/config/GatewayProperties.java");
    }

    private static String readText(Path p) {
        try {
            return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
