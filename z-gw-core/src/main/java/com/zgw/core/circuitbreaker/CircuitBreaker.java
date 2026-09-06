package com.zgw.core.circuitbreaker;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

/**
 * Circuit breaker implementation
 */
public class CircuitBreaker {

    private static final Logger logger = LogManager.getLogger(CircuitBreaker.class);
    // Configuration
    private final String name;
    private final int failureThreshold;
    private final int successThreshold;
    private final Duration timeoutDuration;
    private final Duration halfOpenMaxCalls;
    private final AtomicInteger failureCount;
    private final AtomicInteger successCount;
    private final AtomicInteger halfOpenCalls;
    // Metrics
    private final LongAdder totalCalls;
    private final LongAdder totalFailures;
    private final LongAdder totalSuccesses;
    // State
    private volatile State state;
    private volatile long lastFailureTime;
    private volatile long stateChangedTime;

    public CircuitBreaker(String name) {
        this(name, new Builder());
    }

    public CircuitBreaker(String name, Builder builder) {
        this.name = name;
        this.failureThreshold = builder.failureThreshold;
        this.successThreshold = builder.successThreshold;
        this.timeoutDuration = builder.timeoutDuration;
        this.halfOpenMaxCalls = builder.halfOpenMaxCalls;

        this.state = State.CLOSED;
        this.failureCount = new AtomicInteger(0);
        this.successCount = new AtomicInteger(0);
        this.halfOpenCalls = new AtomicInteger(0);
        this.lastFailureTime = 0;
        this.stateChangedTime = System.currentTimeMillis();

        this.totalCalls = new LongAdder();
        this.totalFailures = new LongAdder();
        this.totalSuccesses = new LongAdder();
    }

    /**
     * Execute a call with circuit breaker protection
     */
    public <T> T execute(ThrowableSupplier<T> supplier) throws Exception {
        if (!allowRequest()) {
            throw new CircuitBreakerOpenException("Circuit breaker is OPEN for: " + name);
        }

        totalCalls.increment();

        try {
            T result = supplier.get();
            recordSuccess();
            return result;
        } catch (Exception e) {
            recordFailure();
            throw e;
        }
    }

    /**
     * Check if request is allowed
     */
    public boolean allowRequest() {
        State currentState = state;

        if (currentState == State.CLOSED) {
            return true;
        }

        if (currentState == State.OPEN) {
            // Check if timeout has passed
            if (System.currentTimeMillis() - stateChangedTime >= timeoutDuration.toMillis()) {
                transitionTo(State.HALF_OPEN);
                return true;
            }
            return false;
        }

        // HALF_OPEN state
        return halfOpenCalls.get() < halfOpenMaxCalls.toMillis();
    }

    /**
     * Record a successful call
     */
    public void recordSuccess() {
        totalSuccesses.increment();

        State currentState = state;

        if (currentState == State.HALF_OPEN) {
            int successes = successCount.incrementAndGet();
            halfOpenCalls.decrementAndGet();

            if (successes >= successThreshold) {
                transitionTo(State.CLOSED);
            }
        } else if (currentState == State.CLOSED) {
            // Reset failure count on success in closed state
            failureCount.set(0);
        }
    }

    /**
     * Record a failed call
     */
    public void recordFailure() {
        totalFailures.increment();

        State currentState = state;

        if (currentState == State.HALF_OPEN) {
            halfOpenCalls.decrementAndGet();
            transitionTo(State.OPEN);
            return;
        }

        if (currentState == State.CLOSED) {
            int failures = failureCount.incrementAndGet();
            lastFailureTime = System.currentTimeMillis();

            if (failures >= failureThreshold) {
                transitionTo(State.OPEN);
            }
        }
    }

    /**
     * Transition to a new state
     */
    private void transitionTo(State newState) {
        State oldState = state;
        state = newState;
        stateChangedTime = System.currentTimeMillis();

        // Reset counters
        failureCount.set(0);
        successCount.set(0);
        halfOpenCalls.set(0);

        logger.info("Circuit breaker '{}' state changed from {} to {}", name, oldState, newState);
    }

    // Getters
    public String getName() {
        return name;
    }

    public State getState() {
        return state;
    }

    public long getTotalCalls() {
        return totalCalls.sum();
    }

    public long getTotalFailures() {
        return totalFailures.sum();
    }

    public long getTotalSuccesses() {
        return totalSuccesses.sum();
    }

    public double getFailureRate() {
        long total = totalCalls.sum();
        return total == 0 ? 0.0 : (double) totalFailures.sum() / total;
    }

    // Circuit breaker states
    public enum State {
        CLOSED,      // Normal operation
        OPEN,        // Circuit open, requests fail fast
        HALF_OPEN    // Testing if service has recovered
    }

    /**
     * Functional interface for supplier that can throw exceptions
     */
    @FunctionalInterface
    public interface ThrowableSupplier<T> {
        T get() throws Exception;
    }

    /**
     * Builder for circuit breaker
     */
    public static class Builder {
        private int failureThreshold = 5;
        private int successThreshold = 3;
        private Duration timeoutDuration = Duration.ofSeconds(30);
        private Duration halfOpenMaxCalls = Duration.ofMillis(3);

        public Builder failureThreshold(int threshold) {
            this.failureThreshold = threshold;
            return this;
        }

        public Builder successThreshold(int threshold) {
            this.successThreshold = threshold;
            return this;
        }

        public Builder timeoutDuration(Duration duration) {
            this.timeoutDuration = duration;
            return this;
        }

        public Builder halfOpenMaxCalls(int maxCalls) {
            this.halfOpenMaxCalls = Duration.ofMillis(maxCalls);
            return this;
        }

        public CircuitBreaker build(String name) {
            return new CircuitBreaker(name, this);
        }
    }

    /**
     * Exception thrown when circuit breaker is open
     */
    public static class CircuitBreakerOpenException extends RuntimeException {
        public CircuitBreakerOpenException(String message) {
            super(message);
        }
    }
}