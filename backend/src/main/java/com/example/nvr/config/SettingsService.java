package com.example.nvr.config;

import com.example.nvr.imsi.ImsiSyncConfigService;
import com.example.nvr.persistence.DatabaseConfig;
import com.example.nvr.persistence.DynamicDataSourceManager;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

@Service
public class SettingsService {

    private static final Logger log = LoggerFactory.getLogger(SettingsService.class);
    private static final Path CONFIG_FILE = Paths.get("config-settings.json");
    private static final Path LEGACY_CONFIG_FILE = Paths.get("config").resolve("settings.json");

    private final ObjectMapper objectMapper;
    private final ImsiSyncConfigService imsiSyncConfigService;
    private final DynamicDataSourceManager dynamicDataSourceManager;

    private SettingsConfig current;

    public SettingsService(ObjectMapper objectMapper,
                           ImsiSyncConfigService imsiSyncConfigService,
                           DynamicDataSourceManager dynamicDataSourceManager) {
        this.objectMapper = objectMapper;
        this.imsiSyncConfigService = imsiSyncConfigService;
        this.dynamicDataSourceManager = dynamicDataSourceManager;
    }

    @PostConstruct
    public void init() {
        synchronized (this) {
            this.current = loadFromDisk();
            applyToDependents(this.current);
        }
    }

    public synchronized SettingsConfig getCurrentConfig() {
        return current;
    }

    public synchronized SettingsConfig update(SettingsConfig updated) {
        SettingsConfig sanitized = (updated == null ? SettingsConfig.defaultConfig() : updated.fillDefaults());
        this.current = sanitized;
        persistToDisk(this.current);
        applyToDependents(this.current);
        return this.current;
    }

    private SettingsConfig loadFromDisk() {
        migrateLegacyFileIfNeeded();
        try {
            if (Files.exists(CONFIG_FILE)) {
                SettingsConfig loaded = objectMapper.readValue(CONFIG_FILE.toFile(), SettingsConfig.class);
                return loaded.fillDefaults();
            }
            SettingsConfig defaults = SettingsConfig.defaultConfig();
            persistToDisk(defaults);
            return defaults;
        } catch (IOException ex) {
            log.warn("Failed to load settings config, using defaults: {}", ex.getMessage());
            SettingsConfig defaults = SettingsConfig.defaultConfig();
            persistToDisk(defaults);
            return defaults;
        }
    }

    private void migrateLegacyFileIfNeeded() {
        if (Files.exists(CONFIG_FILE) || !Files.exists(LEGACY_CONFIG_FILE)) {
            return;
        }
        try {
            Files.move(LEGACY_CONFIG_FILE, CONFIG_FILE, StandardCopyOption.REPLACE_EXISTING);
            log.info("Migrated settings configuration to {}", CONFIG_FILE.toAbsolutePath());
        } catch (IOException moveEx) {
            log.warn("Failed to move legacy settings file: {}", moveEx.getMessage());
            try {
                SettingsConfig legacy = objectMapper.readValue(LEGACY_CONFIG_FILE.toFile(), SettingsConfig.class);
                SettingsConfig sanitized = legacy.fillDefaults();
                persistToDisk(sanitized);
                Files.deleteIfExists(LEGACY_CONFIG_FILE);
                log.info("Copied legacy settings configuration to new location");
            } catch (IOException copyEx) {
                log.warn("Failed to migrate legacy settings configuration: {}", copyEx.getMessage());
            }
        }
    }

    private void persistToDisk(SettingsConfig config) {
        try {
            Path parent = CONFIG_FILE.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(CONFIG_FILE.toFile(), config);
        } catch (IOException ex) {
            log.warn("Failed to write settings config: {}", ex.getMessage());
        }
    }

    private void applyToDependents(SettingsConfig config) {
        if (config == null) {
            return;
        }
        imsiSyncConfigService.applySettings(config);
        applyDatabaseSettings(config);
    }

    private void applyDatabaseSettings(SettingsConfig config) {
        if (config == null) {
            return;
        }
        try {
            DatabaseConfig databaseConfig = new DatabaseConfig(
                    config.getDbType(),
                    config.getDbHost(),
                    config.getDbPort() == null ? 0 : config.getDbPort(),
                    config.getDbName(),
                    config.getDbUser(),
                    config.getDbPass()
            );
            dynamicDataSourceManager.update(databaseConfig);
        } catch (Exception ex) {
            log.warn("Failed to apply database settings: {}", ex.getMessage());
        }
    }
}
