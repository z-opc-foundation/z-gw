package com.zgw.core.router;

/**
 * Result of route matching
 */
public class RouteResult {

    private final String routeId;
    private final String path;
    private final String backend;
    private final String targetUri;
    private final boolean stripPrefix;

    public RouteResult(String routeId, String path, String backend, String targetUri, boolean stripPrefix) {
        this.routeId = routeId;
        this.path = path;
        this.backend = backend;
        this.targetUri = targetUri;
        this.stripPrefix = stripPrefix;
    }

    public String getRouteId() {
        return routeId;
    }

    public String getPath() {
        return path;
    }

    public String getBackend() {
        return backend;
    }

    public String getTargetUri() {
        return targetUri;
    }

    public boolean isStripPrefix() {
        return stripPrefix;
    }

    @Override
    public String toString() {
        return "RouteResult{" +
                "routeId='" + routeId + '\'' +
                ", path='" + path + '\'' +
                ", backend='" + backend + '\'' +
                ", targetUri='" + targetUri + '\'' +
                ", stripPrefix=" + stripPrefix +
                '}';
    }
}