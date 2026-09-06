package com.zgw.core.loadbalance;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * Round Robin Load Balancer
 */
public class RoundRobinLoadBalancer implements LoadBalancer {

    private final AtomicInteger counter = new AtomicInteger(0);

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

        // Round robin selection
        int index = Math.abs(counter.getAndIncrement()) % healthyInstances.size();
        return healthyInstances.get(index);
    }

    @Override
    public String getName() {
        return "roundRobin";
    }
}