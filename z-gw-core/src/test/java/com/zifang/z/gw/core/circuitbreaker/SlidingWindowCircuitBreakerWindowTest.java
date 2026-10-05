package com.zifang.z.gw.core.circuitbreaker;

import com.zifang.z.gw.api.CircuitBreaker;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SlidingWindowCircuitBreaker} 必须是<b>真滑动窗口</b>。
 *
 * <p>{@code windowSize} / {@code windowDurationMs} 此前只有声明和 getter，判定逻辑一次
 * 都没读过：窗口实际是"从上次恢复起累计、永不过期"的两只计数器。于是两类故障同时存在——
 * ① 一批老失败会无限期压着失败率，后端早就恢复熔断器还开着；② {@code windowDurationMs}
 * 参与除法却没人用，构造期也不校验，传 0 不报错。</p>
 *
 * <p><b>判别式的构造</b>：注意 {@code shouldTrip} 只在 {@code recordFailure} 里被调用
 * （既有语义，本次未改），所以每组数据都以<b>一次失败收尾</b>，确保判定真的被触发。
 * 前后两段用<b>同一组数据</b>，只差"中间睡不睡"——若实现不看时间，两段会给出同一答案。</p>
 */
class SlidingWindowCircuitBreakerWindowTest {

    private static final CircuitBreaker.State CLOSED = CircuitBreaker.State.CLOSED;
    private static final CircuitBreaker.State OPEN = CircuitBreaker.State.OPEN;
    private static final CircuitBreaker.State HALF_OPEN = CircuitBreaker.State.HALF_OPEN;


    /** 失败率 50%、最小流量 20。15 次失败 + 10 次成功 + 1 次失败 = 25/16 → 61% 越线。 */
    private static SlidingWindowCircuitBreaker breaker(int windowSize, long windowDurationMs) {
        return new SlidingWindowCircuitBreaker("t", 50, 20, 50_000L, windowSize, windowDurationMs);
    }

    private static void failures(SlidingWindowCircuitBreaker cb, int n) {
        for (int i = 0; i < n; i++) {
            cb.recordFailure("k", 1, new RuntimeException("boom"));
        }
    }

    private static void successes(SlidingWindowCircuitBreaker cb, int n) {
        for (int i = 0; i < n; i++) {
            cb.recordSuccess("k", 1);
        }
    }

    /**
     * 判别式的统一动作：15 次失败 → 睡 → 10 次成功 → 1 次失败（收尾触发判定）。
     *
     * @param expect 期望的最终状态
     */
    private static void tripOrNot(SlidingWindowCircuitBreaker cb, long sleepMs,
                                  CircuitBreaker.State expect, String why) throws InterruptedException {
        failures(cb, 15);
        assertEquals(CLOSED, cb.currentState("k"),
                "前置：15 次失败总量未达 requestVolumeThreshold=20，本身不该熔断");
        if (sleepMs > 0) {
            Thread.sleep(sleepMs);
        }
        successes(cb, 10);
        failures(cb, 1);          // 必须以失败收尾，shouldTrip 才会被调用

        assertEquals(expect, cb.currentState("k"), why);
    }

    // ==================================================================
    // windowDurationMs：同一 sleep，只改窗口跨度
    // ==================================================================

    @Test
    @DisplayName("windowDurationMs=100：老失败滑出窗口后不再压失败率")
    void shortWindowAgesOutFailures() throws Exception {
        // 窗口只覆盖最近 100ms，睡了 400ms ⇒ 15 次失败全部滑出
        tripOrNot(breaker(1, 100L), 400L, CLOSED,
                "老失败滑出窗口后仍把失败率抬到阈值以上 —— 窗口根本没在滑，"
                        + "windowSize/windowDurationMs 是死参数");
    }

    @Test
    @DisplayName("windowDurationMs=5000：同样的间隔，老失败还活着（证明该参数参与判定）")
    void longWindowKeepsFailuresAlive() throws Exception {
        // 窗口 5 秒，400ms 相对它微不足道 ⇒ 15 次失败仍在窗口内
        tripOrNot(breaker(1, 5000L), 400L, OPEN,
                "windowDurationMs=5000 时这 15 次失败还该在窗口内 —— 该参数没参与判定");
    }

    // ==================================================================
    // windowSize：同一 sleep + 同一 windowDurationMs，只改桶数
    // ==================================================================

    @Test
    @DisplayName("windowSize=10：同样的 400ms，老失败仍在窗口内（证明 windowSize 也参与）")
    void moreBucketsWidenTheSpan() throws Exception {
        // 10 桶 × 100ms = 1000ms 窗口 > 400ms ⇒ 老失败仍在窗口内
        tripOrNot(breaker(10, 100L), 400L, OPEN,
                "windowSize=10（窗口 1000ms）时 400ms 后老失败仍该在窗口内 —— windowSize 没参与判定");
    }

    @Test
    @DisplayName("windowSize=1：同样的 400ms，老失败已滑出（与上一条只差桶数）")
    void oneBucketNarrowerWindowAgesOut() throws Exception {
        // 1 桶 × 100ms = 100ms 窗口 < 400ms ⇒ 老失败已滑出
        tripOrNot(breaker(1, 100L), 400L, CLOSED,
                "windowSize=1（窗口 100ms）时 400ms 后老失败必须已滑出 —— windowSize 没参与判定");
    }

    @Test
    @DisplayName("窗口内的失败照样熔断（对照组：证明上面几条不是把阈值改松了）")
    void failuresInsideWindowStillTrip() {
        SlidingWindowCircuitBreaker cb = breaker(1, 100L);

        failures(cb, 15);
        assertEquals(CLOSED, cb.currentState("k"), "前置：15 次失败本身不该熔断");
        successes(cb, 10);
        failures(cb, 1);

        // 全程 < 100ms，15 次失败仍在窗口内：16/26 = 61% ≥ 50%
        assertEquals(OPEN, cb.currentState("k"), "窗口内的 60% 失败率没有熔断 —— 阈值判定本身坏了");
    }

    // ==================================================================
    // 构造期 fail-fast
    // ==================================================================

    @Test
    @DisplayName("windowSize<=0 构造即拒（它要当下标用，0 会让取模拿到 -1）")
    void rejectsNonPositiveWindowSize() {
        assertThrows(IllegalArgumentException.class,
                () -> new SlidingWindowCircuitBreaker("t", 50, 20, 1000L, 0, 100L),
                "windowSize=0 应在构造期就被拒");
        assertThrows(IllegalArgumentException.class,
                () -> new SlidingWindowCircuitBreaker("t", 50, 20, 1000L, -3, 100L),
                "windowSize=-3 应在构造期就被拒");
    }

    @Test
    @DisplayName("windowDurationMs<=0 构造即拒（它参与除法，0 会 ArithmeticException）")
    void rejectsNonPositiveWindowDuration() {
        assertThrows(IllegalArgumentException.class,
                () -> new SlidingWindowCircuitBreaker("t", 50, 20, 1000L, 10, 0L),
                "windowDurationMs=0 应在构造期就被拒");
        assertThrows(IllegalArgumentException.class,
                () -> new SlidingWindowCircuitBreaker("t", 50, 20, 1000L, 10, -1L),
                "windowDurationMs=-1 应在构造期就被拒");
    }

    @Test
    @DisplayName("合法参数仍可构造且初始为 CLOSED（对照组：fail-fast 没把正常路径也拒了）")
    void validParamsStillWork() {
        SlidingWindowCircuitBreaker cb = new SlidingWindowCircuitBreaker("t", 50, 20, 1000L, 10, 1000L);
        assertEquals(CLOSED, cb.currentState("k"));
        assertTrue(cb.allowRequest("k"));
    }

    // ==================================================================
    // resetWindow：半开恢复后老计数确实清空
    // ==================================================================

    @Test
    @DisplayName("半开恢复后窗口被清空：紧接着的少量失败不该立刻二次熔断")
    void windowIsClearedOnRecovery() throws InterruptedException {
        SlidingWindowCircuitBreaker cb = new SlidingWindowCircuitBreaker(
                "t", 50, 20, 1L /*sleepWindow*/, 5, 60_000L /*窗口 300s，足够大*/);

        failures(cb, 40);
        assertEquals(OPEN, cb.currentState("k"), "前置：40 次全失败应熔断");

        // sleepWindow=1ms，但首次 allowRequest 可能落在同一毫秒（elapsed=0 不 >= 1），
        // 所以轮询推进到半开，而不是假定一次就进得去。
        long deadline = System.currentTimeMillis() + 2000;
        while (cb.currentState("k") != HALF_OPEN && System.currentTimeMillis() < deadline) {
            cb.allowRequest("k");
            Thread.sleep(5);
        }
        assertEquals(HALF_OPEN, cb.currentState("k"), "sleepWindow 过后应进半开");

        for (int i = 0; i < 5 && cb.currentState("k") != CLOSED; i++) {
            cb.allowRequest("k");
            cb.recordSuccess("k", 1);
        }
        assertEquals(CLOSED, cb.currentState("k"), "3 次半开成功应恢复 CLOSED");

        // 窗口已清空：只剩 4 次失败，总量 4 < 20，不该再熔断
        failures(cb, 4);
        assertEquals(CLOSED, cb.currentState("k"),
                "恢复后只发生 4 次失败（< requestVolumeThreshold=20）却再次熔断 —— 窗口没被清空");
    }
}
