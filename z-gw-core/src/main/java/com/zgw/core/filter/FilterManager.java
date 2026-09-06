package com.zgw.core.filter;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/**
 * Filter manager for registering and managing filters
 */
public class FilterManager {

    private static final Logger logger = LogManager.getLogger(FilterManager.class);

    private final Map<String, Filter> filtersByName;
    private final List<Filter> filters;
    private volatile FilterChain chain;

    public FilterManager() {
        this.filtersByName = new ConcurrentHashMap<>();
        this.filters = new CopyOnWriteArrayList<>();
        this.chain = new FilterChain(new ArrayList<>());

        // Load filters from SPI
        loadSpiFilters();
    }

    public static FilterManager getInstance() {
        return Holder.INSTANCE;
    }

    /**
     * Register a filter
     */
    public void register(Filter filter) {
        if (filter == null) {
            return;
        }

        String name = filter.name();
        if (filtersByName.containsKey(name)) {
            logger.warn("Filter with name '{}' already exists, replacing", name);
        }

        filtersByName.put(name, filter);
        filters.add(filter);
        sortFilters();
        rebuildChain();

        logger.info("Registered filter: {} (order: {}, type: {})",
                name, filter.order(), filter.type());
    }

    /**
     * Unregister a filter
     */
    public void unregister(String name) {
        Filter filter = filtersByName.remove(name);
        if (filter != null) {
            filters.remove(filter);
            rebuildChain();
            logger.info("Unregistered filter: {}", name);
        }
    }

    /**
     * Get filter by name
     */
    public Filter getFilter(String name) {
        return filtersByName.get(name);
    }

    /**
     * Check if filter exists
     */
    public boolean hasFilter(String name) {
        return filtersByName.containsKey(name);
    }

    /**
     * Get the filter chain
     */
    public FilterChain getChain() {
        return chain;
    }

    /**
     * Get all registered filters
     */
    public List<Filter> getFilters() {
        return new ArrayList<>(filters);
    }

    /**
     * Get filters by type
     */
    public List<Filter> getFiltersByType(Filter.FilterType type) {
        return filters.stream()
                .filter(f -> f.type() == type)
                .collect(Collectors.toList());
    }

    /**
     * Clear all filters
     */
    public void clear() {
        filtersByName.clear();
        filters.clear();
        rebuildChain();
        logger.info("Cleared all filters");
    }

    /**
     * Load filters from SPI
     */
    private void loadSpiFilters() {
        ServiceLoader<Filter> loader = ServiceLoader.load(Filter.class);
        for (Filter filter : loader) {
            register(filter);
        }
    }

    /**
     * Sort filters by order
     */
    private void sortFilters() {
        filters.sort(Comparator.comparingInt(Filter::order));
    }

    /**
     * Rebuild the filter chain
     */
    private void rebuildChain() {
        this.chain = new FilterChain(new ArrayList<>(filters));
    }

    // Singleton instance
    private static class Holder {
        private static final FilterManager INSTANCE = new FilterManager();
    }
}