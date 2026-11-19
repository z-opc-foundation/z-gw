package com.zifang.z.gw.admin.controller;

import com.zifang.z.gw.api.RouteDefinition;
import com.zifang.z.gw.core.router.InMemoryRouteRepository;
import com.zifang.z.gw.core.router.RouteMatcher;
import com.zifang.z.meta.api.annotation.BizApi;
import com.zifang.z.meta.api.annotation.BizField;
import com.zifang.z.meta.api.annotation.BizParam;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 路由管理 REST API — 提供 CRUD + reload + 批量替换。
 *
 * <p>端点:
 * <ul>
 *   <li>{@code GET    /gw/admin/routes}         — 列出全部路由</li>
 *   <li>{@code GET    /gw/admin/routes/{id}}    — 获取单条路由</li>
 *   <li>{@code POST   /gw/admin/routes}         — 创建路由</li>
 *   <li>{@code PUT    /gw/admin/routes/{id}}    — 更新路由</li>
 *   <li>{@code DELETE /gw/admin/routes/{id}}    — 删除路由</li>
 *   <li>{@code POST   /gw/admin/routes/reload}  — 整体 reload</li>
 *   <li>{@code GET    /gw/admin/routes/stats}   — 统计信息</li>
 * </ul>
 */
@RestController
@RequestMapping("/gw/admin/routes")
@Tag(name = "路由管理")
public class RouteAdminController {

    private final InMemoryRouteRepository repository;
    private final RouteMatcher matcher;

    @Autowired
    public RouteAdminController(InMemoryRouteRepository repository, RouteMatcher matcher) {
        this.repository = repository;
        this.matcher = matcher;
    }

    @GetMapping
    @Operation(summary = "列出全部路由")
    @BizApi(summary = "list", description = "", tags = { "z-gw" })
    public List<RouteDefinition> list() {
        return repository.getRouteDefinitions();
    }

    @GetMapping("/{id}")
    @Operation(summary = "获取单条路由")
    @BizApi(summary = "get", description = "", tags = { "z-gw" })
    public ResponseEntity<RouteDefinition> get(@PathVariable @BizParam(name = "id", type = BizField.FieldType.STRING, description = "", position = BizParam.Position.PATH) String id) {
        RouteDefinition r = repository.get(id);
        return r == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(r);
    }

    @PostMapping
    @Operation(summary = "创建路由")
    @BizApi(summary = "create", description = "", tags = { "z-gw" })
    public ResponseEntity<RouteDefinition> create(@RequestBody @BizParam(name = "route", type = BizField.FieldType.STRING, description = "", position = BizParam.Position.BODY) RouteDefinition route) {
        if (route.getId() == null || route.getId().isEmpty()) {
            return ResponseEntity.badRequest().build();
        }
        repository.save(route);
        return ResponseEntity.ok(route);
    }

    @PutMapping("/{id}")
    @Operation(summary = "更新路由")
    @BizApi(summary = "update", description = "", tags = { "z-gw" })
    public ResponseEntity<RouteDefinition> update(@PathVariable @BizParam(name = "id", type = BizField.FieldType.STRING, description = "", position = BizParam.Position.PATH) String id,
                                                 @RequestBody @BizParam(name = "route", type = BizField.FieldType.STRING, description = "", position = BizParam.Position.BODY) RouteDefinition route) {
        if (!id.equals(route.getId())) {
            return ResponseEntity.badRequest().build();
        }
        repository.save(route);
        return ResponseEntity.ok(route);
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "删除路由")
    @BizApi(summary = "delete", description = "", tags = { "z-gw" })
    public ResponseEntity<Void> delete(@PathVariable @BizParam(name = "id", type = BizField.FieldType.STRING, description = "", position = BizParam.Position.PATH) String id) {
        repository.delete(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/reload")
    @Operation(summary = "整体 reload 路由")
    @BizApi(summary = "reload", description = "", tags = { "z-gw" })
    public ResponseEntity<Map<String, Object>> reload(@RequestBody @BizParam(name = "routes", type = BizField.FieldType.JSON, description = "", position = BizParam.Position.BODY) List<RouteDefinition> routes) {
        repository.replaceAll(routes);
        return ResponseEntity.ok(
                Collections.singletonMap("count", (Object) (routes == null ? 0 : routes.size())));
    }

    @GetMapping("/stats")
    @Operation(summary = "路由统计信息")
    @BizApi(summary = "stats", description = "", tags = { "z-gw" })
    public Map<String, Object> stats() {
        List<RouteDefinition> routes = repository.getRouteDefinitions();
        int enabled = 0;
        for (RouteDefinition r : routes) {
            if (r.isEnabled()) enabled++;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", routes.size());
        out.put("enabled", enabled);
        out.put("disabled", routes.size() - enabled);
        return out;
    }
}



