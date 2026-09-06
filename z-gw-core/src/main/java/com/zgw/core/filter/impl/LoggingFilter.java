package com.zgw.core.filter.impl;

import com.zgw.core.filter.Filter;
import com.zgw.core.filter.FilterChain;
import com.zgw.core.server.GatewayContext;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Logging filter for request/response logging
 */
public class LoggingFilter implements Filter {

    private static final Logger logger = LogManager.getLogger(LoggingFilter.class);

    @Override
    public String name() {
        return "logging";
    }

    @Override
    public int order() {
        return 100;
    }

    @Override
    public FilterType type() {
        return FilterType.PRE;
    }

    @Override
    public void execute(GatewayContext context, FilterChain chain) {
        long startTime = System.currentTimeMillis();
        context.setAttribute("startTime", startTime);

        if (logger.isDebugEnabled()) {
            logger.debug("[{}] {} {} - Start",
                    context.getRequestId(),
                    context.getRequest().method(),
                    context.getRequest().uri());
        }

        // Continue chain
        chain.execute(context);

        // Log response (will be called after chain completes)
        long duration = System.currentTimeMillis() - startTime;
        context.setAttribute("duration", duration);

        if (logger.isInfoEnabled()) {
            logger.info("[{}] {} {} - {} - {}ms",
                    context.getRequestId(),
                    context.getRequest().method(),
                    context.getRequest().uri(),
                    context.getResponse() != null ? context.getResponse().status().code() : "-",
                    duration);
        }
    }
}