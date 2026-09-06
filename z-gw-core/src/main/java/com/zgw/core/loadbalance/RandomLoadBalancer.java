package com.zgw.core.loadbalance;

import java.util.List;
import java.util.Random;
import java.util.stream.Collectors;

/**
 * Random Load Balancer
 */
public class RandomLoadBalancer implements LoadBalancer {

    private final Random random = new Random();

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

        // Random selection
        int index = random.nextInt(healthyInstances.size());
        return healthyInstances.get(index);
    }

    @Override
    public String getName() {
        return "random";
    }
}