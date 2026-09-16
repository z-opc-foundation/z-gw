package com.zifang.z.gw.admin.controller;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * 网关指标 API — 聚合 Micrometer 的 zgw.* 指标。
 *
 * <p>前端用此数据做简易仪表盘;Prometheus 部署用 /actuator/prometheus。
 */
@RestController
@RequestMapping("/gw/admin/metrics")
@Tag(name = "网关指标")
public class MetricsController {

    private final MeterRegistry registry;

    @Autowired
    public MetricsController(MeterRegistry registry) {
        this.registry = registry;
    }

    @GetMapping("/summary")
    @Operation(summary = "聚合请求数 + 平均 RT")
    public Map<String, Object> summary() {
        Map<String, Object> out = new HashMap<>();

        double totalReq = registry.find("zgw.request.total").counters().stream()
                .mapToDouble(c -> c.count()).sum();
        out.put("totalRequests", totalReq);

        registry.find("zgw.request.duration").timers().forEach(t -> {
            String key = t.getId().getTag("route") + ":" + t.getId().getTag("status");
            out.put("rt:" + key, t.mean(java.util.concurrent.TimeUnit.MILLISECONDS));
            out.put("count:" + key, t.count());
        });
        return out;
    }

    @GetMapping("/routes")
    @Operation(summary = "按路由维度汇总")
    public Map<String, Map<String, Number>> perRoute() {
        Map<String, Map<String, Number>> result = new HashMap<>();
        registry.find("zgw.request.total").counters().forEach(c -> {
            String route = c.getId().getTag("route");
            if (route == null) return;
            result.computeIfAbsent(route, k -> new HashMap<>())
                    .merge("requests", c.count(), (a, b) -> a.doubleValue() + b.doubleValue());
        });
        return result;
    }
}
