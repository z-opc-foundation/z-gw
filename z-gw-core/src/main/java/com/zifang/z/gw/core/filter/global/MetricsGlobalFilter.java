package com.zifang.z.gw.core.filter.global;

import com.zifang.z.gw.api.GatewayContext;
import com.zifang.z.gw.api.GatewayFilter;
import com.zifang.z.gw.api.GatewayFilterChain;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 指标采集全局过滤器 — 记录 QPS / RT / 状态码 / 错误数,通过 Micrometer 暴露。
 *
 * <p>对接 Prometheus 时由 {@code io.micrometer:micrometer-registry-prometheus} 提供 endpoint。
 */
public class MetricsGlobalFilter implements GatewayFilter {

    private static final Logger log = LoggerFactory.getLogger(MetricsGlobalFilter.class);
    public static final String NAME = "Metrics";

    private final MeterRegistry meterRegistry;
    private final AtomicLong inFlight = new AtomicLong(0);

    public MetricsGlobalFilter() {
        this(io.micrometer.core.instrument.Metrics.globalRegistry);
    }

    public MetricsGlobalFilter(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public int order() {
        return -900;
    }

    @Override
    public void filter(GatewayContext ctx, GatewayFilterChain chain) {
        inFlight.incrementAndGet();
        long start = System.nanoTime();
        try {
            chain.filter(ctx);
        } finally {
            long costNs = System.nanoTime() - start;
            inFlight.decrementAndGet();

            String routeId = ctx.getMatchedRoute() != null ? ctx.getMatchedRoute().getId() : "unmatched";
            String status = (String) ctx.getAttribute("resp.status", String.class);
            if (status == null) status = "unknown";

            try {
                Tags tags = Tags.of("route", routeId, "method", ctx.getMethod() == null ? "UNKNOWN" : ctx.getMethod(), "status", status);
                Timer.builder("zgw.request.duration")
                        .tags(tags)
                        .publishPercentileHistogram()
                        .register(meterRegistry)
                        .record(costNs, TimeUnit.NANOSECONDS);

                Counter.builder("zgw.request.total")
                        .tags(tags)
                        .register(meterRegistry)
                        .increment();
            } catch (Exception e) {
                log.debug("Metrics record failed", e);
            }
        }
    }

    public long getInFlight() {
        return inFlight.get();
    }
}
