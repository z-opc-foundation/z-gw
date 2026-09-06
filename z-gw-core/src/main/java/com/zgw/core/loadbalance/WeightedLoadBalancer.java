package com.zgw.core.loadbalance;

import java.util.List;
import java.util.Random;
import java.util.stream.Collectors;

/**
 * Weighted Random Load Balancer
 */
public class WeightedLoadBalancer implements LoadBalancer {

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

        // Calculate total weight
        int totalWeight = healthyInstances.stream()
                .mapToInt(Instance::getWeight)
                .sum();

        if (totalWeight <= 0) {
            // Fall back to random if no valid weights
            return healthyInstances.get(random.nextInt(healthyInstances.size()));
        }

        // Weighted random selection
        int randomWeight = random.nextInt(totalWeight);
        int currentWeight = 0;

        for (Instance instance : healthyInstances) {
            currentWeight += instance.getWeight();
            if (randomWeight < currentWeight) {
                return instance;
            }
        }

        // Should not reach here, but just in case
        return healthyInstances.get(healthyInstances.size() - 1);
    }

    @Override
    public String getName() {
        return "weighted";
    }
}