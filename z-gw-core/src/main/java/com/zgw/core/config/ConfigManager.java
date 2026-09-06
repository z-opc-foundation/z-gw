package com.zgw.core.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Configuration manager
 */
public class ConfigManager {

    private static final Logger logger = LogManager.getLogger(ConfigManager.class);

    private static final String DEFAULT_CONFIG_PATH = "conf/gateway.yaml";

    private final ObjectMapper yamlMapper;
    private GatewayConfig config;

    public ConfigManager() {
        this.yamlMapper = new ObjectMapper(new YAMLFactory());
        this.yamlMapper.findAndRegisterModules();
    }

    public static ConfigManager getInstance() {
        return Holder.INSTANCE;
    }

    /**
     * Load configuration from default path
     */
    public void load() throws IOException {
        load(DEFAULT_CONFIG_PATH);
    }

    /**
     * Load configuration from specified path
     */
    public void load(String configPath) throws IOException {
        logger.info("Loading configuration from: {}", configPath);

        // Try to load from file system first
        Path path = Paths.get(configPath);
        if (Files.exists(path)) {
            config = yamlMapper.readValue(path.toFile(), GatewayConfig.class);
            logger.info("Loaded configuration from file: {}", path.toAbsolutePath());
            return;
        }

        // Try to load from classpath
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(configPath)) {
            if (is != null) {
                config = yamlMapper.readValue(is, GatewayConfig.class);
                logger.info("Loaded configuration from classpath: {}", configPath);
                return;
            }
        }

        // If no config found, use default
        logger.warn("No configuration file found, using default configuration");
        config = new GatewayConfig();
    }

    /**
     * Get current configuration
     */
    public GatewayConfig getConfig() {
        return config;
    }

    /**
     * Reload configuration
     */
    public void reload() throws IOException {
        logger.info("Reloading configuration...");
        load();
    }

    /**
     * Get singleton instance
     */
    private static class Holder {
        private static final ConfigManager INSTANCE = new ConfigManager();
    }
}