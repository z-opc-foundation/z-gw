package com.zgw.core.filter;

import com.zgw.core.server.GatewayContext;

/**
 * Filter interface for request/response processing
 */
public interface Filter {

    /**
     * Filter name
     */
    String name();

    /**
     * Filter order - lower value means higher priority
     */
    int order();

    /**
     * Filter type
     */
    FilterType type();

    /**
     * Execute filter logic
     *
     * @param context gateway context
     * @param chain   filter chain
     */
    void execute(GatewayContext context, FilterChain chain);

    /**
     * Whether this filter should be executed for this request
     *
     * @param context gateway context
     * @return true if filter should be executed
     */
    default boolean shouldFilter(GatewayContext context) {
        return true;
    }

    /**
     * Filter type enum
     */
    enum FilterType {
        PRE,        // Pre-routing filters
        ROUTING,    // Routing filters
        POST,       // Post-routing filters
        ERROR       // Error filters
    }
}