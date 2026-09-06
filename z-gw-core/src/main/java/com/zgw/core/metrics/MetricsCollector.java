package com.zgw.core.metrics;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * Metrics collector for gateway monitoring
 */
public class MetricsCollector {

    private static final Logger logger = LogManager.getLogger(MetricsCollector.class);

    // Counter metrics
    private final Map<String, LongAdder> counters;

    // Gauge metrics
    private final Map<String, AtomicLong> gauges;

    // Histogram metrics (simplified - just count and sum)
    private final Map<String, Histogram> histograms;

    // Request timing
    private final LongAdder totalRequests;
    private final LongAdder totalErrors;
    private final LongAdder totalRequestTime;

    public MetricsCollector() {
        this.counters = new ConcurrentHashMap<>();
        this.gauges = new ConcurrentHashMap<>();
        this.histograms = new ConcurrentHashMap<>();
        this.totalRequests = new LongAdder();
        this.totalErrors = new LongAdder();
        this.totalRequestTime = new LongAdder();
    }

    /**
     * Record a request
     */
    public void recordRequest(String route, long durationMs, boolean success) {
        totalRequests.increment();
        totalRequestTime.add(durationMs);

        incrementCounter("requests.total");
        incrementCounter("requests.route." + route);

        if (!success) {
            totalErrors.increment();
            incrementCounter("errors.total");
            incrementCounter("errors.route." + route);
        }

        // Record latency histogram
        recordHistogram("latency", durationMs);
        recordHistogram("latency.route." + route, durationMs);

        if (logger.isDebugEnabled()) {
            logger.debug("Recorded request: route={}, duration={}ms, success={}",
                    route, durationMs, success);
        }
    }

    /**
     * Increment a counter
     */
    public void incrementCounter(String name) {
        counters.computeIfAbsent(name, k -> new LongAdder()).increment();
    }

    /**
     * Increment a counter by delta
     */
    public void incrementCounter(String name, long delta) {
        counters.computeIfAbsent(name, k -> new LongAdder()).add(delta);
    }

    /**
     * Set a gauge value
     */
    public void setGauge(String name, long value) {
        gauges.computeIfAbsent(name, k -> new AtomicLong()).set(value);
    }

    /**
     * Record a histogram value
     */
    public void recordHistogram(String name, long value) {
        histograms.computeIfAbsent(name, k -> new Histogram()).record(value);
    }

    /**
     * Get counter value
     */
    public long getCounter(String name) {
        LongAdder counter = counters.get(name);
        return counter != null ? counter.sum() : 0;
    }

    /**
     * Get gauge value
     */
    public long getGauge(String name) {
        AtomicLong gauge = gauges.get(name);
        return gauge != null ? gauge.get() : 0;
    }

    /**
     * Get histogram statistics
     */
    public HistogramStats getHistogramStats(String name) {
        Histogram histogram = histograms.get(name);
        return histogram != null ? histogram.getStats() : new HistogramStats(0, 0, 0);
    }

    /**
     * Get all metrics snapshot
     */
    public MetricsSnapshot getSnapshot() {
        return new MetricsSnapshot(
                totalRequests.sum(),
                totalErrors.sum(),
                totalRequestTime.sum(),
                getQps(),
                getAverageLatency(),
                getErrorRate()
        );
    }

    /**
     * Get current QPS
     */
    public double getQps() {
        // Simplified - in production, use sliding window
        long requests = totalRequests.sum();
        return requests / 60.0; // Per minute average
    }

    /**
     * Get average latency
     */
    public double getAverageLatency() {
        long requests = totalRequests.sum();
        if (requests == 0) {
            return 0;
        }
        return (double) totalRequestTime.sum() / requests;
    }

    /**
     * Get error rate
     */
    public double getErrorRate() {
        long requests = totalRequests.sum();
        if (requests == 0) {
            return 0;
        }
        return (double) totalErrors.sum() / requests;
    }

    /**
     * Reset all metrics
     */
    public void reset() {
        counters.clear();
        gauges.clear();
        histograms.clear();
        totalRequests.reset();
        totalErrors.reset();
        totalRequestTime.reset();
    }

    // Inner classes for histogram support

    private static class Histogram {
        private final LongAdder count = new LongAdder();
        private final LongAdder sum = new LongAdder();
        private final AtomicLong min = new AtomicLong(Long.MAX_VALUE);
        private final AtomicLong max = new AtomicLong(Long.MIN_VALUE);

        void record(long value) {
            count.increment();
            sum.add(value);
            updateMin(value);
            updateMax(value);
        }

        private void updateMin(long value) {
            long current;
            do {
                current = min.get();
            } while (value < current && !min.compareAndSet(current, value));
        }

        private void updateMax(long value) {
            long current;
            do {
                current = max.get();
            } while (value > current && !max.compareAndSet(current, value));
        }

        HistogramStats getStats() {
            long count = this.count.sum();
            if (count == 0) {
                return new HistogramStats(0, 0, 0);
            }
            return new HistogramStats(
                    (double) sum.sum() / count,
                    min.get(),
                    max.get()
            );
        }
    }

    public static class HistogramStats {
        private final double mean;
        private final long min;
        private final long max;

        public HistogramStats(double mean, long min, long max) {
            this.mean = mean;
            this.min = min;
            this.max = max;
        }

        public double getMean() {
            return mean;
        }

        public long getMin() {
            return min;
        }

        public long getMax() {
            return max;
        }

        @Override
        public String toString() {
            return String.format("HistogramStats{mean=%.2f, min=%d, max=%d}", mean, min, max);
        }
    }

    public static class MetricsSnapshot {
        private final long totalRequests;
        private final long totalErrors;
        private final long totalRequestTime;
        private final double qps;
        private final double averageLatency;
        private final double errorRate;

        public MetricsSnapshot(long totalRequests, long totalErrors, long totalRequestTime,
                               double qps, double averageLatency, double errorRate) {
            this.totalRequests = totalRequests;
            this.totalErrors = totalErrors;
            this.totalRequestTime = totalRequestTime;
            this.qps = qps;
            this.averageLatency = averageLatency;
            this.errorRate = errorRate;
        }

        // Getters
        public long getTotalRequests() {
            return totalRequests;
        }

        public long getTotalErrors() {
            return totalErrors;
        }

        public long getTotalRequestTime() {
            return totalRequestTime;
        }

        public double getQps() {
            return qps;
        }

        public double getAverageLatency() {
            return averageLatency;
        }

        public double getErrorRate() {
            return errorRate;
        }

        @Override
        public String toString() {
            return String.format(
                    "MetricsSnapshot{requests=%d, errors=%d, qps=%.2f, latency=%.2fms, errorRate=%.2f%%}",
                    totalRequests, totalErrors, qps, averageLatency, errorRate * 100
            );
        }
    }
}