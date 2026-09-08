package com.zifang.z.gw.api;

/**
 * 谓词评估结果 — 替代 SCG 的 {@code Predicate} 是函数式接口的设计,
 * 用一个 result 对象携带更多信息 (matched / reason / 提取的 path variable)。
 *
 * <p>设计为不可变 — 评估失败时给出 reason 方便日志/调试。
 */
public final class PredicateResult {

    private final boolean matched;
    private final String reason;

    private PredicateResult(boolean matched, String reason) {
        this.matched = matched;
        this.reason = reason;
    }

    public static final PredicateResult MATCH = new PredicateResult(true, null);
    public static PredicateResult match() { return MATCH; }

    public static PredicateResult noMatch(String reason) {
        return new PredicateResult(false, reason);
    }

    public boolean isMatched() { return matched; }
    public boolean isNotMatched() { return !matched; }
    public String getReason() { return reason; }

    @Override
    public String toString() {
        return matched ? "MATCH" : ("NO_MATCH[" + reason + "]");
    }
}
