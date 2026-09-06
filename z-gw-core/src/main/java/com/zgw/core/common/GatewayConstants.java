package com.zgw.core.common;

/**
 * Gateway constants
 */
public final class GatewayConstants {

    // Default server port
    public static final int DEFAULT_PORT = 8080;
    // Default buffer sizes
    public static final int DEFAULT_BUFFER_SIZE = 8192;
    public static final int MAX_CONTENT_LENGTH = 10 * 1024 * 1024; // 10MB
    // Connection pool settings
    public static final int DEFAULT_CONNECTION_TIMEOUT_MS = 3000;
    public static final int DEFAULT_READ_TIMEOUT_MS = 30000;
    public static final int DEFAULT_WRITE_TIMEOUT_MS = 30000;
    // HTTP headers
    public static final String X_REQUEST_ID = "X-Request-Id";
    public static final String X_FORWARDED_FOR = "X-Forwarded-For";
    public static final String X_REAL_IP = "X-Real-IP";
    public static final String X_GATEWAY_TIME = "X-Gateway-Time";
    // Default encoding
    public static final String UTF_8 = "UTF-8";
    // Load balance strategies
    public static final String LB_ROUND_ROBIN = "roundRobin";
    public static final String LB_RANDOM = "random";
    public static final String LB_WEIGHTED = "weighted";
    public static final String LB_LEAST_CONNECTIONS = "leastConnections";
    public static final String LB_IP_HASH = "ipHash";
    // Rate limiter types
    public static final String RL_TOKEN_BUCKET = "tokenBucket";
    public static final String RL_SLIDING_WINDOW = "slidingWindow";
    public static final String RL_FIXED_WINDOW = "fixedWindow";

    private GatewayConstants() {
        // utility class
    }
}