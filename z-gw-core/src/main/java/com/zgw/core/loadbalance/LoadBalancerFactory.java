package com.zgw.core.loadbalance;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Map;
import java.util.ServiceLoader;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Load balancer factory
 */
public class LoadBalancerFactory {

    private static final Logger logger = LogManager.getLogger(LoadBalancerFactory.class);

    private static final Map<String, LoadBalancer> LOAD_BALANCERS = new ConcurrentHashMap<>();

    static {
        // Register built-in load balancers
        register(new RoundRobinLoadBalancer());
        register(new RandomLoadBalancer());
        register(new WeightedLoadBalancer());
        register(new LeastConnectionsLoadBalancer());
        register(new IpHashLoadBalancer());

        // Load from SPI
        ServiceLoader<LoadBalancer> loader = ServiceLoader.load(LoadBalancer.class);
        for (LoadBalancer lb : loader) {
            register(lb);
        }
    }

    /**
     * Register a load balancer
     */
    public static void register(LoadBalancer loadBalancer) {
        LOAD_BALANCERS.put(loadBalancer.getName(), loadBalancer);
        logger.info("Registered load balancer: {}", loadBalancer.getName());
    }

    /**
     * Get load balancer by name
     */
    public static LoadBalancer get(String name) {
        LoadBalancer lb = LOAD_BALANCERS.get(name);
        if (lb == null) {
            logger.warn("Load balancer not found: {}, using roundRobin", name);
            return LOAD_BALANCERS.get("roundRobin");
        }
        return lb;
    }

    /**
     * Get default load balancer (round robin)
     */
    public static LoadBalancer getDefault() {
        return LOAD_BALANCERS.get("roundRobin");
    }

    /**
     * Check if load balancer exists
     */
    public static boolean exists(String name) {
        return LOAD_BALANCERS.containsKey(name);
    }

    /**
     * Get all registered load balancer names
     */
    public static String[] getNames() {
        return LOAD_BALANCERS.keySet().toArray(new String[0]);
    }
}