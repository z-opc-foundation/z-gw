package com.zifang.z.gw.core.circuitbreaker;

import com.zifang.z.gw.api.CircuitBreaker;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SlidingWindowCircuitBreaker} 必须是<b>真滑动窗口</b>，且片的边界与请求无关。
 *
 * <p>{@code windowSize} / {@code windowDurationMs} 此前只有声明和 getter，判定逻辑一次
 * 都没读过：窗口实际是"从上次恢复起累计、永不过期"的两只计数器。于是两类故障同时存在——
 * ① 一批老失败会无限期压着失败率，后端早就恢复熔断器还开着；② {@code windowDurationMs}
 * 参与除法却没人用，构造期也不校验，传 0 不报错。</p>
 *
 * <p><b>判别式的构造</b>：注意 {@code shouldTrip} 只在 {@code recordFailure} 里被调用
 * （既有语义），所以每组数据都以<b>一次失败收尾</b>，确保判定真的被触发。
 * 前后两段用<b>同一组数据</b>，只差"中间过不推进时钟"——若实现不看时间，两段会给出同一答案。</p>
 *
 * <p><b>时钟可注入</b>（{@code LongSupplier}，与 {@code SlidingWindowRateLimiter} 同一形状），
 * 所以这里一条 {@code Thread.sleep} 都没有。此前它们靠真 sleep + 墙钟整除的片边界，
 * 于是 {@code longWindowKeepsFailuresAlive} 是<b>概率性 flaky</b>：400ms 的 sleep 落到
 * 5000ms 周期的跨界处时（概率约 8%）整段统计被清零，实测复跑 3 次为 1 红 2 绿。
 * 片起点改为锚在本 key 的首次进入之后，同一组数据在任何时刻跑都是同一个答案。</p>
 */
class SlidingWindowCircuitBreakerWindowTest {

    private static final CircuitBreaker.State CLOSED = CircuitBreaker.State.CLOSED;
    private static final CircuitBreaker.State OPEN = CircuitBreaker.State.OPEN;
    private static final CircuitBreaker.State HALF_OPEN = CircuitBreaker.State.HALF_OPEN;

    /** 可手工推进的时钟。起 100_000L 是个不对齐任何整除点的普通时刻。 */
    private static final AtomicLong NOW = new AtomicLong(100_000L);

    /** 失败率 50%、最小流量 20。15 次失败 + 10 次成功 + 1 次失败 = 26/16 → 61% 越线。 */
    private static SlidingWindowCircuitBreaker breaker(int windowSize, long windowDurationMs) {
        NOW.set(100_000L);
        return new SlidingWindowCircuitBreaker("t", 50, 20, 50_000L, windowSize, windowDurationMs, NOW::get);
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

    private static void successesOn(String key, SlidingWindowCircuitBreaker cb, int n) {
        for (int i = 0; i < n; i++) {
            cb.recordSuccess(key, 1);
        }
    }

    private static void failuresOn(String key, SlidingWindowCircuitBreaker cb, int n) {
        for (int i = 0; i < n; i++) {
            cb.recordFailure(key, 1, new RuntimeException("boom"));
        }
    }

    /**
     * 判别式的统一动作：15 次失败 → 推进时钟 → 10 次成功 → 1 次失败（收尾触发判定）。
     *
     * @param expect 期望的最终状态
     */
    private static void tripOrNot(SlidingWindowCircuitBreaker cb, long advanceMs,
                                  CircuitBreaker.State expect, String why) {
        failures(cb, 15);
        assertEquals(CLOSED, cb.currentState("k"),
                "前置：15 次失败总量未达 requestVolumeThreshold=20，本身不该熔断");
        if (advanceMs > 0) {
            NOW.addAndGet(advanceMs);
        }
        successes(cb, 10);
        failures(cb, 1);          // 必须以失败收尾，shouldTrip 才会被调用

        assertEquals(expect, cb.currentState("k"), why);
    }

    // ==================================================================
    // windowDurationMs：同一间隔，只改窗口跨度
    // ==================================================================

    @Test
    @DisplayName("windowDurationMs=100：老失败滑出窗口后不再压失败率")
    void shortWindowAgesOutFailures() {
        // 窗口只覆盖最近 100ms，推进 400ms ⇒ 15 次失败全部滑出
        tripOrNot(breaker(1, 100L), 400L, CLOSED,
                "老失败滑出窗口后仍把失败率抬到阈值以上 —— 窗口根本没在滑，"
                        + "windowSize/windowDurationMs 是死参数");
    }

    @Test
    @DisplayName("windowDurationMs=5000：同样的间隔，老失败还活着（证明该参数参与判定）")
    void longWindowKeepsFailuresAlive() {
        // 窗口 5 秒，推进 400ms 相对它微不足道 ⇒ 15 次失败仍在窗口内
        tripOrNot(breaker(1, 5000L), 400L, OPEN,
                "windowDurationMs=5000 时这 15 次失败还该在窗口内 —— 该参数没参与判定");
    }

    // ==================================================================
    // windowSize：同一间隔 + 同一 windowDurationMs，只改桶数
    // ==================================================================

    @Test
    @DisplayName("windowSize=10：同样的 400ms，老失败仍在窗口内（证明 windowSize 也参与）")
    void moreBucketsWidenTheSpan() {
        // 10 桶 × 100ms = 1000ms 窗口 > 400ms ⇒ 老失败仍在窗口内
        tripOrNot(breaker(10, 100L), 400L, OPEN,
                "windowSize=10（窗口 1000ms）时 400ms 后老失败仍该在窗口内 —— windowSize 没参与判定");
    }

    @Test
    @DisplayName("windowSize=1：同样的 400ms，老失败已滑出（与上一条只差桶数）")
    void oneBucketNarrowerWindowAgesOut() {
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

        // 同一片内：16/26 = 61% ≥ 50%
        assertEquals(OPEN, cb.currentState("k"), "窗口内的 60% 失败率没有熔断 —— 阈值判定本身坏了");
    }

    // ==================================================================
    // 失败率阈值：恰好等于阈值时算不算越线
    // ==================================================================

    /**
     * 失败率<b>正好落在阈值上</b>时必须熔断（{@code >=} 而不是 {@code >}）。
     *
     * <p>上面那些判据的失败率都离阈值很远（61%、100%、33%、50% 对 60%），
     * 所以把 {@code pct >= errorThresholdPercentage} 改成 {@code pct > ...}
     * 一条都不会变色。这条的失败率<b>恰好等于</b>阈值：同一片内 10 次成功 + 10 次失败
     * = 20 次、10 次失败 = 50%，阈值也正是 50。</p>
     *
     * <p>用 {@code windowSize=1} + 单片：全部落在同一片，不受滑窗干扰，
     * 于是「整除 vs 不整除」这件事本身成了唯一的变量。</p>
     */
    @Test
    @DisplayName("失败率恰好等于阈值时必须熔断（>= 而不是 >）")
    void failureRateExactlyAtThresholdTrips() {
        NOW.set(100_000L);
        SlidingWindowCircuitBreaker cb =
                new SlidingWindowCircuitBreaker("t", 50, 20, 50_000L, 1, 100L, NOW::get);

        successes(cb, 9);
        failures(cb, 9);           // 9/18 = 50%，但 total=18 < 20 ⇒ 不该熔断
        assertEquals(CLOSED, cb.currentState("k"),
                "前置：18 次里 9 次失败，失败率虽为 50% 但总量未达 20，本不该熔断");

        successes(cb, 1);          // 补到 19 次
        failures(cb, 1);           // 10/20 = 50% = 阈值 ⇒ 恰好越线

        assertEquals(OPEN, cb.currentState("k"),
                "失败率恰好等于阈值 50% 时没有熔断 —— 阈值判定用了严格大于；"
                        + "配置写的是\"失败率阈值(0-100)\"，达到阈值就应当越线，"
                        + "否则把阈值调低一档才能生效，配置口径与实现不符");
    }

    // ==================================================================
    // 片边界：锚在本 key 的首次进入，而不是墙钟整除点
    // ==================================================================

    /**
     * 窗口内的失败率**不该**因为"片边界碰巧落在哪"而变。
     *
     * <p>片时长 1000ms。t=100900 记 10 次成功 + 5 次失败（15 次、失败率 33%）；
     * 推进 <b>300ms</b> 到 t=101200 再记 5 次失败。这 300ms 距首次进入不到一个片，
     * 所以<b>那 10 次成功还该在窗口里</b> —— 累计 20 次、失败 10 次 = 50% &lt; 60%
     * ⇒ 不该熔断。</p>
     *
     * <p>按墙钟整除的旧实现里，这 300ms 恰好越过了 {@code 100000} 那个整除点
     * （片 100 → 101），那 10 次成功被丢掉，于是剩下 5 次失败单独算成 100%
     * ⇒ <b>熔断</b>。也就是<b>样本越少越容易熔断</b>，方向正好反了：
     * 真实流量越稀疏，熔断器越容易被一串零星失败打断。</p>
     */
    @Test
    @DisplayName("片边界不得落在墙钟整除点上：没跨片时老样本必须还在窗口内")
    void windowBoundaryIsAnchoredToFirstEventNotWallClock() {
        NOW.set(100_900L);
        SlidingWindowCircuitBreaker cb =
                new SlidingWindowCircuitBreaker("t", 60, 5, 50_000L, 1, 1000L, NOW::get);

        successes(cb, 10);
        failures(cb, 5);
        assertEquals(CLOSED, cb.currentState("k"), "前置：5/15 = 33% < 60%，不该熔断");

        // +300ms：跨过了 100000 这个整除点（墙钟整除的实现会因此翻片），
        // 但距本 key 首次进入只有 300ms，不足一个片
        NOW.addAndGet(300L);
        failures(cb, 5);       // 新实现：累计 20 次、失败 10 次 = 50% < 60%

        assertEquals(CLOSED, cb.currentState("k"),
                "距首次进入只有 300ms（< 一个 1000ms 的片），那 10 次成功必须还在窗口里；"
                        + "若被判为熔断，说明片边界跟着墙钟整除点走了 —— "
                        + "越过 100000/1000 那个整除点时会把同片的成功样本丢掉，"
                        + "让失败率随样本量反向漂移");
    }

    /**
     * 对照组：<b>真的</b>跨过一个片之后，老样本必须滑出。
     *
     * <p>上一条若只是"永远不清窗"也会绿，这条把它按住。</p>
     */
    @Test
    @DisplayName("对照组：跨过一个片之后老样本确实滑出（证明上一条不是把窗口钉死了）")
    void samplesStillAgeOutAfterAFullSlot() {
        NOW.set(100_900L);
        SlidingWindowCircuitBreaker cb =
                new SlidingWindowCircuitBreaker("t", 60, 5, 50_000L, 1, 1000L, NOW::get);

        successes(cb, 10);
        failures(cb, 5);

        NOW.addAndGet(1000L);  // 距首次进入满一个片，老样本该滑出
        failures(cb, 5);        // 窗口里只剩这 5 次失败 = 100% ≥ 60% ⇒ 熔断

        assertEquals(OPEN, cb.currentState("k"),
                "推进满一个片时长后老样本必须滑出；没滑出说明窗口被钉死了");
    }

    /**
     * 每个 key 各有自己的片起点（此前片号是全局墙钟算的，所有 key 共享同一条边界）。
     *
     * <p><b>这条是结构性判据，对两种实现都绿</b>——它钉的是"状态确实按 key 隔离"，
     * 不负责区分片锚点。要区分靠上面那条。</p>
     */
    @Test
    @DisplayName("不同 key 的片起点互不影响（结构性：对新旧实现都成立）")
    void eachKeyAnchorsItsOwnSlot() {
        NOW.set(100_000L);
        SlidingWindowCircuitBreaker cb =
                new SlidingWindowCircuitBreaker("t", 60, 5, 50_000L, 1, 1000L, NOW::get);

        successesOn("a", cb, 5);
        failuresOn("a", cb, 5);
        assertEquals(CLOSED, cb.currentState("a"));

        NOW.addAndGet(700L);
        // b 在此刻首次出现：它的窗口从现在开始，a 的那 5 次成功与它无关
        successesOn("b", cb, 5);
        failuresOn("b", cb, 5);
        assertEquals(CLOSED, cb.currentState("b"), "前置：b 自己 5/10 = 33% < 60%");
        assertEquals(CLOSED, cb.currentState("a"), "b 的流量不该影响 a 的失败率");
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
    @DisplayName("clockMs=null 构造即拒（否则片起点无从锚定）")
    void rejectsNullClock() {
        assertThrows(IllegalArgumentException.class,
                () -> new SlidingWindowCircuitBreaker("t", 50, 20, 1000L, 10, 100L, null),
                "clockMs=null 应在构造期就被拒");
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
    void windowIsClearedOnRecovery() {
        NOW.set(100_000L);
        SlidingWindowCircuitBreaker cb = new SlidingWindowCircuitBreaker(
                "t", 50, 20, 1000L /*sleepWindow*/, 5, 60_000L /*窗口 300s，足够大*/, NOW::get);

        failures(cb, 40);
        assertEquals(OPEN, cb.currentState("k"), "前置：40 次全失败应熔断");

        // sleepWindow=1000ms，推进时钟跨过去再进半开
        NOW.addAndGet(1000L);
        assertTrue(cb.allowRequest("k"));
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
