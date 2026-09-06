package com.zgw.starter;

import com.zgw.core.config.ConfigManager;
import com.zgw.core.config.GatewayConfig;
import com.zgw.core.server.GatewayServer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;

/**
 * Gateway bootstrap entry point
 */
public class GatewayBootstrap {

    private static final Logger logger = LogManager.getLogger(GatewayBootstrap.class);

    public static void main(String[] args) {
        logger.info("╔══════════════════════════════════════════╗");
        logger.info("║      Z-GW Gateway Starting...            ║");
        logger.info("║      Version: 1.0.0-SNAPSHOT               ║");
        logger.info("╚══════════════════════════════════════════╝");

        try {
            // Load configuration
            ConfigManager configManager = ConfigManager.getInstance();
            String configPath = System.getProperty("gateway.config", "conf/gateway.yaml");
            configManager.load(configPath);

            GatewayConfig config = configManager.getConfig();
            logger.info("Configuration loaded: {}", config);

            // Start server
            GatewayServer server = new GatewayServer(config);
            server.start();

            // Add shutdown hook
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                logger.info("Shutdown signal received");
                server.shutdown();
            }));

            // Wait for server to terminate
            server.awaitTermination();

        } catch (IOException e) {
            logger.error("Failed to load configuration", e);
            System.exit(1);
        } catch (InterruptedException e) {
            logger.info("Gateway server interrupted");
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            logger.error("Failed to start gateway server", e);
            System.exit(1);
        }

        logger.info("Z-GW Gateway stopped");
    }
}