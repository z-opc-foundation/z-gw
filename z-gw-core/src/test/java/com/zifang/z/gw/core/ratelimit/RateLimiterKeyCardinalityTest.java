package com.zifang.z.gw.core.ratelimit;

import com.zifang.z.gw.api.RateLimiter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 三个限流器的 keyed 状态表<b>不许无界增长</b>。
 *
 * <p>改这一层的原因不是"内存洁癖"，而是一条可被远程触发的路径：限流键默认取
 * {@code clientIp}（{@code RateLimitFilterFactory.resolveKey} 里
 * {@code keyResolver == null} 就走这一条），而 {@code clientIp} 来自
 * {@code GatewayHandler.buildContext} 读的<b>客户端自带</b> {@code X-Forwarded-For} 首段。
 * 攻击者每个请求换一个 XFF，就能在三个 map 里各留下一条<b>永不回收</b>的条目 ——
 * 堆持续增长直到网关 OOM。</p>
 *
 * <p>清理判据不是另发明的一套，而是<b>各实现自身已经用着的"这条状态已无信息"边界</b>，
 * 所以清理对限流行为零影响：</p>
 * <ul>
 *   <li>固定窗口：{@code windowSecond} 早于当前秒 ⇒ 下次进来本来就会被归零</li>
 *   <li>滑动窗口：10 个槽全部早于 {@code now - 1000L} ⇒ 下次进来本来就会被清零</li>
 *   <li>令牌桶：距上次补充已够补满一整桶 ⇒ 与 {@code new Bucket(...)}（构造即满）等价</li>
 * </ul>
 *
 * <p>⚠ 本类用 JUnit 5 的 {@code assertTrue(条件, 消息)}，
 * <b>不是</b> JUnit 4 的 {@code assertTrue(消息, 条件)} —— 后者在 JUnit 5 里不存在，
 * 写反了会得到一串 "no suitable method found for assertTrue(String, boolean)"。</p>
 */
class RateLimiterKeyCardinalityTest {

    /**
     * 轮换次数取 20000 —— 远大于门槛 1024。
     * <p>
     * 断言的是<b>有界</b>而不是"立刻回收"：清理在 {@code tryAcquire} 入口按
     * {@code size() > CLEANUP_THRESHOLD} 触发，所以流量掉下去之后 map 会停在高水位
     * （本例约 800）直到再次越过门槛 —— 那仍然是有界的，不清理的话这里会一路涨到 20000。
     * <p>
     * 上界取 {@code 门槛 + 1}：扫描发生在插入之前，所以最后一次是在
     * {@code size() == 门槛} 时跳过扫描、随后插到 {@code 门槛 + 1}。
     */
    private static final int DISTINCT_KEYS = 20000;
    private static final int BOUND = 1025;

    /**
     * 建模真实的攻击形态：<b>持续</b>轮换 XFF，而不是一次性突发 4000 个。
     * <p>
     * 一次性突发时那 4000 条状态<b>确实都是活跃的</b>，清理若把它们删掉反而是错的；
     * 真正要证明的是：条目在空闲后被回收，于是稳态大小只与"速率 × 窗口"有关，
     * <b>与累计请求数无关</b>。不清理的话这里会一路涨到 4000。
     */
    private static void sustainedRotation(RateLimiter rl, AtomicLong clock, long step) {
        for (int i = 0; i < DISTINCT_KEYS; i++) {
            rl.tryAcquire("attacker-ip-" + i);
            if (i % 20 == 0) {
                clock.addAndGet(step);   // 每 20 个请求推进一"步"
            }
        }
    }

    // ==================================================================
    // 固定窗口
    // ==================================================================

    @Test
    @DisplayName("固定窗口：大量不同 key 后状态表必须收缩（修复前无界增长）")
    void fixedWindowDoesNotGrowUnbounded() {
        AtomicLong clock = new AtomicLong(1_000_000L);
        FixedWindowRateLimiter rl = new FixedWindowRateLimiter(10, clock::get);

        sustainedRotation(rl, clock, 50L);
        assertTrue(rl.trackedKeys() <= BOUND,
                "持续轮换下状态表必须稳定在【速率×窗口】量级，而不是随累计请求数增长: "
                        + rl.trackedKeys());
    }

    @Test
    @DisplayName("固定窗口：清理不得影响仍在窗口内的 key（对照组）")
    void fixedWindowKeepsLiveKeysWorking() {
        AtomicLong clock = new AtomicLong(1_000_000L);
        FixedWindowRateLimiter rl = new FixedWindowRateLimiter(3, clock::get);

        for (int i = 0; i < 3; i++) {
            assertTrue(rl.tryAcquire("live").isAllowed(), "第 " + i + " 次应当放行");
        }
        assertFalse(rl.tryAcquire("live").isAllowed(), "超出额度必须被拒");

        // 造足够多的不同 key 把扫描触发起来，再回到那个 key 上
        for (int i = 0; i < DISTINCT_KEYS; i++) {
            rl.tryAcquire("filler-" + i);
        }
        // 仍在同一秒内 ⇒ 那个 key 的额度必须没有被清掉
        assertFalse(rl.tryAcquire("live").isAllowed(),
                "同一秒内再打一次仍应被拒（说明清理没误伤活跃 key）");
    }

    // ==================================================================
    // 滑动窗口
    // ==================================================================

    @Test
    @DisplayName("滑动窗口：大量不同 key 后状态表必须收缩")
    void slidingWindowDoesNotGrowUnbounded() {
        AtomicLong clock = new AtomicLong(1_000_000L);
        SlidingWindowRateLimiter rl = new SlidingWindowRateLimiter(10, clock::get);

        sustainedRotation(rl, clock, 50L);
        assertTrue(rl.trackedKeys() <= BOUND, "状态表随累计请求数增长: " + rl.trackedKeys());
    }

    @Test
    @DisplayName("滑动窗口：清理不得放走额度（对照组）")
    void slidingWindowKeepsLiveKeysWorking() {
        AtomicLong clock = new AtomicLong(1_000_000L);
        SlidingWindowRateLimiter rl = new SlidingWindowRateLimiter(3, clock::get);

        for (int i = 0; i < 3; i++) {
            assertTrue(rl.tryAcquire("live").isAllowed(), "第 " + i + " 次应当放行");
        }
        assertFalse(rl.tryAcquire("live").isAllowed(), "超出额度必须被拒");

        for (int i = 0; i < DISTINCT_KEYS; i++) {
            rl.tryAcquire("filler-" + i);
        }
        assertFalse(rl.tryAcquire("live").isAllowed(),
                "同一窗口内再打一次仍应被拒（说明清理没误伤活跃 key）");
    }

    // ==================================================================
    // 令牌桶
    // ==================================================================

    @Test
    @DisplayName("令牌桶：大量不同 key 后桶表必须收缩")
    void tokenBucketDoesNotGrowUnbounded() {
        AtomicLong clock = new AtomicLong(1_000_000L);
        // 容量 10、每秒补 10 个 ⇒ 补满一桶要 1 秒
        TokenBucketRateLimiter rl = new TokenBucketRateLimiter(10, 10, clock::get);

        sustainedRotation(rl, clock, 50_000_000L);
        assertTrue(rl.trackedKeys() <= BOUND, "桶表随累计请求数增长: " + rl.trackedKeys());
    }

    @Test
    @DisplayName("令牌桶：刚被用过的桶不许被当成空闲删掉（对照组）")
    void tokenBucketKeepsJustUsedBucket() {
        AtomicLong clock = new AtomicLong(1_000_000L);
        TokenBucketRateLimiter rl = new TokenBucketRateLimiter(3, 1, clock::get);

        for (int i = 0; i < 3; i++) {
            assertTrue(rl.tryAcquire("live").isAllowed(), "第 " + i + " 次应当放行");
        }
        assertFalse(rl.tryAcquire("live").isAllowed(), "桶已空，应当被拒");

        // 只推进 100ms：补满 3 个令牌要 3 秒，所以这个桶**还没**补满，不能删
        clock.addAndGet(100_000_000L);
        for (int i = 0; i < DISTINCT_KEYS; i++) {
            rl.tryAcquire("filler-" + i);
        }
        assertFalse(rl.tryAcquire("live").isAllowed(),
                "刚用过且未补满的桶仍应记着已耗尽的额度（没被误删）");
    }

    @Test
    @DisplayName("三个实现的门槛常量一致（改动其中一个会漏掉另外两个）")
    void allThreeShareTheSameThreshold() {
        assertEquals(FixedWindowRateLimiter.CLEANUP_THRESHOLD, SlidingWindowRateLimiter.CLEANUP_THRESHOLD);
        assertEquals(FixedWindowRateLimiter.CLEANUP_THRESHOLD, TokenBucketRateLimiter.CLEANUP_THRESHOLD);
    }
}
