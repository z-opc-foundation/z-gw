package com.zgw.core.router;

import com.zgw.core.config.GatewayConfig;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpMethod;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Pattern;

/**
 * Simple router implementation
 */
public class SimpleRouter implements Router {

    private static final Logger logger = LogManager.getLogger(SimpleRouter.class);

    private final GatewayConfig config;
    private final List<RouteEntry> routes;
    private final Map<String, RouteEntry> routeMap;

    public SimpleRouter(GatewayConfig config) {
        this.config = config;
        this.routes = new CopyOnWriteArrayList<>();
        this.routeMap = new ConcurrentHashMap<>();
        loadRoutes();
    }

    @Override
    public RouteResult route(FullHttpRequest request) {
        String uri = request.uri();
        HttpMethod method = request.method();

        if (logger.isDebugEnabled()) {
            logger.debug("Routing request: {} {}", method, uri);
        }

        for (RouteEntry entry : routes) {
            if (entry.matches(uri, method.name())) {
                String targetUri = buildTargetUri(uri, entry);
                if (logger.isDebugEnabled()) {
                    logger.debug("Route matched: {} -> {}", uri, targetUri);
                }
                return new RouteResult(
                        entry.getId(),
                        entry.getPath(),
                        entry.getBackend(),
                        targetUri,
                        entry.isStripPrefix()
                );
            }
        }

        logger.warn("No route found for: {} {}", method, uri);
        return null;
    }

    @Override
    public void addRoute(GatewayConfig.RouteDefinition route) {
        RouteEntry entry = new RouteEntry(route);
        routes.add(entry);
        routeMap.put(route.getId(), entry);
        logger.info("Route added: {}", route.getId());
    }

    @Override
    public void removeRoute(String routeId) {
        RouteEntry entry = routeMap.remove(routeId);
        if (entry != null) {
            routes.remove(entry);
            logger.info("Route removed: {}", routeId);
        }
    }

    @Override
    public void reloadRoutes() {
        routes.clear();
        routeMap.clear();
        loadRoutes();
        logger.info("Routes reloaded");
    }

    /**
     * Load routes from configuration
     */
    private void loadRoutes() {
        List<GatewayConfig.RouteDefinition> routeDefinitions = config.getRouter().getRoutes();
        if (routeDefinitions != null) {
            for (GatewayConfig.RouteDefinition route : routeDefinitions) {
                try {
                    addRoute(route);
                } catch (Exception e) {
                    logger.error("Failed to load route: {}", route.getId(), e);
                }
            }
        }
        logger.info("Loaded {} routes", routes.size());
    }

    /**
     * Build target URI for backend
     */
    private String buildTargetUri(String requestUri, RouteEntry entry) {
        String path = requestUri;

        // Remove query string if present
        int queryIndex = path.indexOf('?');
        if (queryIndex != -1) {
            path = path.substring(0, queryIndex);
        }

        // Strip prefix if configured
        if (entry.isStripPrefix()) {
            String prefix = entry.getPath();
            // Convert path pattern to actual prefix
            prefix = prefix.replace("/**", "").replace("/*", "");
            if (path.startsWith(prefix)) {
                path = path.substring(prefix.length());
                if (!path.startsWith("/")) {
                    path = "/" + path;
                }
            }
        }

        // Build target URI
        String backend = entry.getBackend();
        if (backend.endsWith("/") && path.startsWith("/")) {
            path = path.substring(1);
        }

        String targetUri = backend + path;

        // Append query string if present
        if (queryIndex != -1) {
            targetUri += requestUri.substring(queryIndex);
        }

        return targetUri;
    }

    /**
     * Route entry
     */
    private static class RouteEntry {
        private final String id;
        private final String path;
        private final String method;
        private final String backend;
        private final boolean stripPrefix;
        private final Pattern pathPattern;

        public RouteEntry(GatewayConfig.RouteDefinition route) {
            this.id = route.getId();
            this.path = route.getPath();
            this.method = route.getMethod() != null ? route.getMethod() : "*";
            this.backend = route.getBackend();
            this.stripPrefix = route.isStripPrefix();
            this.pathPattern = compilePathPattern(route.getPath());
        }

        /**
         * Check if this entry matches the given URI and method
         */
        public boolean matches(String uri, String requestMethod) {
            // Check method
            if (!method.equals("*") && !method.equalsIgnoreCase(requestMethod)) {
                return false;
            }

            // Remove query string for matching
            String path = uri;
            int queryIndex = path.indexOf('?');
            if (queryIndex != -1) {
                path = path.substring(0, queryIndex);
            }

            // Match path pattern
            return pathPattern.matcher(path).matches();
        }

        public String getId() {
            return id;
        }

        public String getPath() {
            return path;
        }

        public String getMethod() {
            return method;
        }

        public String getBackend() {
            return backend;
        }

        public boolean isStripPrefix() {
            return stripPrefix;
        }

        /**
         * Compile path pattern to regex
         */
        private Pattern compilePathPattern(String path) {
            StringBuilder regex = new StringBuilder();
            int i = 0;
            while (i < path.length()) {
                char c = path.charAt(i);
                if (c == '*') {
                    if (i + 1 < path.length() && path.charAt(i + 1) == '*') {
                        // ** matches any path
                        regex.append(".*");
                        i += 2;
                    } else {
                        // * matches single path segment
                        regex.append("[^/]*");
                        i++;
                    }
                } else if (c == '?') {
                    regex.append(".");
                    i++;
                } else if (isRegexSpecial(c)) {
                    regex.append('\\').append(c);
                    i++;
                } else {
                    regex.append(c);
                    i++;
                }
            }
            return Pattern.compile(regex.toString());
        }

        private boolean isRegexSpecial(char c) {
            return c == '.' || c == '^' || c == '$' || c == '+' ||
                    c == '{' || c == '[' || c == '|' || c == '(' || c == ')' || c == '\\';
        }
    }
}