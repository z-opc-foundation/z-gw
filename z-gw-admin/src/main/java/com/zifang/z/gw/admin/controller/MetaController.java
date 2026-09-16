package com.zifang.z.gw.admin.controller;

import com.zifang.z.gw.api.RouteDefinition;
import com.zifang.z.gw.core.predicate.PredicateFactoryRegistry;
import com.zifang.z.gw.core.router.InMemoryRouteRepository;
import com.zifang.z.gw.core.server.GatewayServer;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 网关元数据 API — 提供 SPI 列表、过滤器列表、状态信息等"字典"。
 *
 * <p>前端管理面板用这些数据构建下拉选项 / 表单字段。
 */
@RestController
@RequestMapping("/gw/admin/meta")
@Tag(name = "网关元数据")
public class MetaController {

    private final PredicateFactoryRegistry predicateRegistry;
    private final InMemoryRouteRepository routeRepository;
    private final GatewayServer server;

    @Autowired
    public MetaController(PredicateFactoryRegistry predicateRegistry,
                          InMemoryRouteRepository routeRepository,
                          GatewayServer server) {
        this.predicateRegistry = predicateRegistry;
        this.routeRepository = routeRepository;
        this.server = server;
    }

    @GetMapping("/predicates")
    @Operation(summary = "列出全部内置谓词工厂")
    public List<Map<String, String>> predicates() {
        List<Map<String, String>> list = new ArrayList<>();
        predicateRegistry.all().forEach(p -> list.add(Map.of("name", p.name())));
        return list;
    }

    @GetMapping("/status")
    @Operation(summary = "网关运行时状态")
    public Map<String, Object> status() {
        return Map.of(
                "started", server != null && server.isStarted(),
                "routes", routeRepository.size()
        );
    }

    @GetMapping("/routes")
    @Operation(summary = "所有路由(同 /gw/admin/routes)")
    public List<RouteDefinition> routes() {
        return routeRepository.getRouteDefinitions();
    }
}
