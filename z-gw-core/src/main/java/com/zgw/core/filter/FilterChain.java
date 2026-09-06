package com.zgw.core.filter;

import com.zgw.core.server.GatewayContext;

import java.util.List;

/**
 * Filter chain for executing filters in order
 */
public class FilterChain {

    private final List<Filter> filters;
    private final int index;
    private final FilterChain next;

    public FilterChain(List<Filter> filters) {
        this.filters = filters;
        this.index = 0;
        this.next = buildChain(filters, 0);
    }

    private FilterChain(List<Filter> filters, int index, FilterChain next) {
        this.filters = filters;
        this.index = index;
        this.next = next;
    }

    private FilterChain buildChain(List<Filter> filters, int startIndex) {
        if (startIndex >= filters.size()) {
            return null;
        }
        return new FilterChain(filters, startIndex + 1,
                buildChain(filters, startIndex + 1));
    }

    /**
     * Execute the filter chain
     */
    public void execute(GatewayContext context) {
        if (index < filters.size()) {
            Filter filter = filters.get(index);

            if (filter.shouldFilter(context)) {
                filter.execute(context, next != null ? next : new FilterChain(filters, filters.size(), null));
            } else {
                // Skip this filter and continue
                if (next != null) {
                    next.execute(context);
                }
            }
        }
    }

    /**
     * Get the list of filters in this chain
     */
    public List<Filter> getFilters() {
        return filters;
    }
}