package com.zgw.core.loadbalance;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Least Connections Load Balancer
 */
public class LeastConnectionsLoadBalancer implements LoadBalancer {

    @Override
    public Instance select(List<Instance> instances, LoadBalanceContext context) {
        if (instances == null || instances.isEmpty()) {
            return null;
        }

        // Filter healthy instances
        List<Instance> healthyInstances = instances.stream()
                .filter(Instance::isHealthy)
                .collect(Collectors.toList());

        if (healthyInstances.isEmpty()) {
            return null;
        }

        // Find instance with least active connections
        Instance selected = null;
        int minConnections = Integer.MAX_VALUE;

        for (Instance instance : healthyInstances) {
            int connections = instance.getActiveConnections();
            // Consider weight: effective connections = actual connections / weight * 100
            int effectiveConnections = connections * 100 / Math.max(instance.getWeight(), 1);

            if (effectiveConnections < minConnections) {
                minConnections = effectiveConnections;
                selected = instance;
            }
        }

        return selected != null ? selected : healthyInstances.get(0);
    }

    @Override
    public String getName() {
        return "leastConnections";
    }
}