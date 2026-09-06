package com.zgw.core.loadbalance;

import java.util.List;
import java.util.stream.Collectors;

/**
 * IP Hash Load Balancer - ensures requests from same IP go to same instance
 */
public class IpHashLoadBalancer implements LoadBalancer {

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

        // Get client IP
        String clientIp = context != null ? context.getClientIp() : null;
        if (clientIp == null || clientIp.isEmpty()) {
            clientIp = "0.0.0.0";
        }

        // Calculate hash
        int hash = clientIp.hashCode();
        int index = Math.abs(hash) % healthyInstances.size();

        return healthyInstances.get(index);
    }

    @Override
    public String getName() {
        return "ipHash";
    }
}