package com.zgw.core.discovery;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Local in-memory service discovery implementation
 */
public class LocalServiceDiscovery implements ServiceDiscovery {

    private static final Logger logger = LogManager.getLogger(LocalServiceDiscovery.class);

    private final Map<String, List<ServiceInstance>> services;
    private final Map<String, List<ServiceChangeListener>> listeners;
    private volatile boolean started = false;

    public LocalServiceDiscovery() {
        this.services = new ConcurrentHashMap<>();
        this.listeners = new ConcurrentHashMap<>();
    }

    @Override
    public void register(String serviceName, ServiceInstance instance) {
        if (!started) {
            throw new IllegalStateException("Service discovery not started");
        }

        services.computeIfAbsent(serviceName, k -> new CopyOnWriteArrayList<>()).add(instance);
        logger.info("Registered instance {} for service {}", instance.getId(), serviceName);

        // Notify listeners
        notifyListeners(serviceName);
    }

    @Override
    public void deregister(String serviceName, String instanceId) {
        if (!started) {
            return;
        }

        List<ServiceInstance> instances = services.get(serviceName);
        if (instances != null) {
            instances.removeIf(i -> i.getId().equals(instanceId));
            logger.info("Deregistered instance {} for service {}", instanceId, serviceName);

            // Notify listeners
            notifyListeners(serviceName);
        }
    }

    @Override
    public List<ServiceInstance> getInstances(String serviceName) {
        return services.getOrDefault(serviceName, new ArrayList<>());
    }

    @Override
    public void watch(String serviceName, ServiceChangeListener listener) {
        listeners.computeIfAbsent(serviceName, k -> new CopyOnWriteArrayList<>()).add(listener);
    }

    @Override
    public void start() {
        if (started) {
            return;
        }
        started = true;
        logger.info("Local service discovery started");
    }

    @Override
    public void stop() {
        if (!started) {
            return;
        }
        started = false;
        services.clear();
        listeners.clear();
        logger.info("Local service discovery stopped");
    }

    private void notifyListeners(String serviceName) {
        List<ServiceChangeListener> serviceListeners = listeners.get(serviceName);
        if (serviceListeners != null) {
            List<ServiceInstance> instances = getInstances(serviceName);
            for (ServiceChangeListener listener : serviceListeners) {
                try {
                    listener.onChange(serviceName, instances);
                } catch (Exception e) {
                    logger.error("Error notifying listener for service {}", serviceName, e);
                }
            }
        }
    }
}