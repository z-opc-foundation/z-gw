package com.zgw.core.router;

import io.netty.handler.codec.http.FullHttpRequest;

/**
 * Router interface for request routing
 */
public interface Router {

    /**
     * Route the request to a backend service
     *
     * @param request the HTTP request
     * @return route result, or null if no route found
     */
    RouteResult route(FullHttpRequest request);

    /**
     * Add or update a route
     *
     * @param route the route definition
     */
    void addRoute(com.zgw.core.config.GatewayConfig.RouteDefinition route);

    /**
     * Remove a route
     *
     * @param routeId the route ID
     */
    void removeRoute(String routeId);

    /**
     * Reload all routes
     */
    void reloadRoutes();
}