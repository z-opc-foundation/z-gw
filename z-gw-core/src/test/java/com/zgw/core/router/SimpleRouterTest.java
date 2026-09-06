package com.zgw.core.router;

import com.zgw.core.config.GatewayConfig;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpVersion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test cases for SimpleRouter
 */
class SimpleRouterTest {

    private GatewayConfig config;
    private SimpleRouter router;

    @BeforeEach
    void setUp() {
        config = new GatewayConfig();

        // Create route definitions
        GatewayConfig.RouteDefinition route1 = new GatewayConfig.RouteDefinition();
        route1.setId("user-service");
        route1.setPath("/api/users/**");
        route1.setMethod("*");
        route1.setBackend("http://localhost:8081");
        route1.setStripPrefix(true);

        GatewayConfig.RouteDefinition route2 = new GatewayConfig.RouteDefinition();
        route2.setId("order-service");
        route2.setPath("/api/orders/**");
        route2.setMethod("GET");
        route2.setBackend("http://localhost:8082");
        route2.setStripPrefix(true);

        GatewayConfig.RouteDefinition route3 = new GatewayConfig.RouteDefinition();
        route3.setId("exact-match");
        route3.setPath("/health");
        route3.setMethod("*");
        route3.setBackend("http://localhost:8083");
        route3.setStripPrefix(false);

        config.getRouter().setRoutes(Arrays.asList(route1, route2, route3));

        router = new SimpleRouter(config);
    }

    @Test
    void testRouteWithWildcard() {
        FullHttpRequest request = createRequest("/api/users/list", HttpMethod.GET);
        RouteResult result = router.route(request);

        assertNotNull(result);
        assertEquals("user-service", result.getRouteId());
        assertEquals("http://localhost:8081/list", result.getTargetUri());
    }

    @Test
    void testRouteWithNestedPath() {
        FullHttpRequest request = createRequest("/api/users/123/orders", HttpMethod.GET);
        RouteResult result = router.route(request);

        assertNotNull(result);
        assertEquals("user-service", result.getRouteId());
        assertEquals("http://localhost:8081/123/orders", result.getTargetUri());
    }

    @Test
    void testRouteWithQueryString() {
        FullHttpRequest request = createRequest("/api/users/list?page=1&size=10", HttpMethod.GET);
        RouteResult result = router.route(request);

        assertNotNull(result);
        assertEquals("user-service", result.getRouteId());
        assertEquals("http://localhost:8081/list?page=1&size=10", result.getTargetUri());
    }

    @Test
    void testRouteMethodNotMatch() {
        // order-service only accepts GET
        FullHttpRequest request = createRequest("/api/orders/list", HttpMethod.POST);
        RouteResult result = router.route(request);

        assertNull(result);
    }

    @Test
    void testExactMatch() {
        FullHttpRequest request = createRequest("/health", HttpMethod.GET);
        RouteResult result = router.route(request);

        assertNotNull(result);
        assertEquals("exact-match", result.getRouteId());
        assertEquals("http://localhost:8083/health", result.getTargetUri());
    }

    @Test
    void testNoRouteFound() {
        FullHttpRequest request = createRequest("/notfound/path", HttpMethod.GET);
        RouteResult result = router.route(request);

        assertNull(result);
    }

    private FullHttpRequest createRequest(String uri, HttpMethod method) {
        return new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, method, uri);
    }
}