package com.zifang.z.gw.core.circuitbreaker;

import com.zifang.z.gw.api.CircuitBreaker;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 半开（HALF_OPEN）期间的在飞计数不许泄漏 —— 否则熔断器会<b>永久卡死</b>。
 *
 * <p>泄漏路径不需要运气，是确定性的：</p>
 * <ol>
 *   <li>A、B 两个请求在半开被放行，{@code halfOpenInFlight} 到 2；</li>
 *   <li>B 先失败 → {@code recordFailure} 在 HALF_OPEN 分支里把 inFlight 减回去，
 *       并把状态置为 <b>OPEN</b>；</li>
 *   <li>A 此刻才回来 → {@code recordSuccess} 看到 {@code current == OPEN}，
 *       <b>不进 HALF_OPEN 分支 ⇒ inFlight 不会被减</b>；</li>
 *   <li>下一个 sleepWindow 到期，OPEN → HALF_OPEN 时只重置了
 *       {@code halfOpenSuccess}/{@code halfOpenFailure}，<b>没重置 inFlight</b>。</li>
 * </ol>
 *
 * <p>⇒ 每经历一轮"半开期间状态翻转"就漏 1 个。{@code allowRequest} 的半开判据是
 * {@code halfOpenInFlight.incrementAndGet() <= 5}，所以漏到 5 个之后
 * <b>再也放不进任何请求</b>；而放不进去就没人调 {@code recordSuccess}，
 * {@code halfOpenSuccess} 永远到不了 3 ⇒ <b>熔断器再也闭不上</b>。
 * 后端早就恢复了，流量却永远被拒——这是最坏的一类"忘了就再也起不来"。</p>
 */
class SlidingWindowCircuitBreakerHalfOpenLeakTest {

    private static final String KEY = "svc";

    /** sleepWindow=0 ⇒ OPEN 立刻可转半开，测试不必真等。 */
    private static SlidingWindowCircuitBreaker trippedBreaker() {
        // errorThreshold=50, volume=1 ⇒ 一次失败就是 100% 失败率，立刻熔断
        SlidingWindowCircuitBreaker cb = new SlidingWindowCircuitBreaker("t", 50, 1, 0L);
        cb.recordFailure(KEY, 0L, new IllegalStateException("boom"));
        return cb;
    }

    @Test
    @DisplayName("对照组：一次失败即熔断，且 sleepWindow=0 时立刻转半开")
    void breakerTripsAndGoesHalfOpenImmediately() {
        CircuitBreaker cb = trippedBreaker();
        assertEquals(CircuitBreaker.State.OPEN, cb.currentState(KEY));

        assertTrue(cb.allowRequest(KEY), "sleepWindow=0，应当立刻允许转入半开");
        assertEquals(CircuitBreaker.State.HALF_OPEN, cb.currentState(KEY));
    }

    /**
     * 核心：反复经历"半开期间一个失败把状态打回 OPEN"，熔断器必须始终还能放行。
     * <p>
     * 修复前这一条在第 5 轮前后就再也放不进来，随后再也闭不上。
     */
    @Test
    @DisplayName("半开期间状态反复翻转不得让熔断器永久卡死")
    void repeatedStateFlipsDoNotPermanentlyStuckTheBreaker() {
        SlidingWindowCircuitBreaker cb = trippedBreaker();
        assertTrue(cb.allowRequest(KEY), "应先转入半开");

        for (int round = 0; round < 10; round++) {
            cb.allowRequest(KEY);      // A 进
            cb.allowRequest(KEY);      // B 进
            cb.recordFailure(KEY, 0L, new IllegalStateException("B 失败"));  // B 先失败 ⇒ 回到 OPEN
            cb.recordSuccess(KEY, 0L); // A 此刻才回来，状态已是 OPEN
            if (cb.currentState(KEY) == CircuitBreaker.State.OPEN) {
                assertTrue(cb.allowRequest(KEY),
                        "第 " + round + " 轮：后端早已恢复，熔断器必须仍能放行");
            }
        }
    }

    @Test
    @DisplayName("泄漏累积后熔断器仍必须能恢复闭合（修复前会永远停在半开）")
    void breakerCanStillCloseAfterRecovery() {
        SlidingWindowCircuitBreaker cb = trippedBreaker();
        assertTrue(cb.allowRequest(KEY));

        for (int round = 0; round < 10; round++) {
            cb.allowRequest(KEY);
            cb.allowRequest(KEY);
            cb.recordFailure(KEY, 0L, new IllegalStateException("B 失败"));
            cb.recordSuccess(KEY, 0L);
            if (cb.currentState(KEY) == CircuitBreaker.State.OPEN) {
                cb.allowRequest(KEY);
            }
        }

        // 后端恢复正常：应当能连上 3 次成功并闭合
        for (int i = 0; i < 3; i++) {
            assertTrue(cb.allowRequest(KEY), "恢复后第 " + i + " 个请求必须放行");
            cb.recordSuccess(KEY, 0L);
        }
        assertEquals(CircuitBreaker.State.CLOSED, cb.currentState(KEY), "3 次成功之后必须闭合");
        assertTrue(cb.allowRequest(KEY), "闭合后当然继续放行");
    }

    @Test
    @DisplayName("对照组：正常的半开流程（无并发翻转）本来就能闭合")
    void normalHalfOpenRecoveryStillCloses() {
        SlidingWindowCircuitBreaker cb = trippedBreaker();
        assertTrue(cb.allowRequest(KEY));

        for (int i = 0; i < 3; i++) {
            assertTrue(cb.allowRequest(KEY));
            cb.recordSuccess(KEY, 0L);
        }
        assertEquals(CircuitBreaker.State.CLOSED, cb.currentState(KEY));
    }
}
