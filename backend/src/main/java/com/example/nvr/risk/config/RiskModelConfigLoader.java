package com.example.nvr.risk.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Loads the 风控模型 YAML once at startup.
 */
@Component
public class RiskModelConfigLoader {

    private static final Logger log = LoggerFactory.getLogger(RiskModelConfigLoader.class);

    private final RiskModelConfig config;

    public RiskModelConfigLoader(ResourceLoader resourceLoader) {
        this.config = load(resourceLoader);
    }

    private RiskModelConfig load(ResourceLoader resourceLoader) {
        try {
            Resource resource = resourceLoader.getResource("classpath:risk/risk-model.yml");
            if (!resource.exists()) {
                throw new IllegalStateException("Missing risk model YAML at classpath:risk/risk-model.yml");
            }
            ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
            mapper.findAndRegisterModules();
            RiskModelConfig loaded = mapper.readValue(resource.getInputStream(), RiskModelConfig.class);
            loaded.validate();
            log.info("Loaded risk model configuration version {}", loaded.getVersion());
            return loaded;
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to load risk model YAML", ex);
        }
    }

    public RiskModelConfig getConfig() {
        return config;
    }
}

