package com.example.nvr.imsi;

import com.example.nvr.config.SettingsConfig;
import com.example.nvr.persistence.ImsiSyncConfigEntity;
import com.example.nvr.persistence.ImsiSyncConfigRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

@Service
public class ImsiSyncConfigService {

    private static final long CONFIG_ID = 1L;
    private static final int DEFAULT_INTERVAL_SECONDS = 300;
    private static final int DEFAULT_BATCH_SIZE = 500;
    private static final int DEFAULT_MAX_FILES = 6;
    private static final String DEFAULT_DEVICE_FILTER = "njtest001";

    private final ImsiSyncConfigRepository repository;

    public ImsiSyncConfigService(ImsiSyncConfigRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public Optional<ImsiSyncConfigEntity> findConfig() {
        return repository.findTopByOrderByIdAsc();
    }

    @Transactional
    public ImsiSyncConfigEntity getOrCreateConfig() {
        return repository.findById(CONFIG_ID).map(entity -> {
            if (entity.getDeviceFilter() == null) {
                entity.setDeviceFilter(DEFAULT_DEVICE_FILTER);
                entity.setUpdatedAt(Instant.now());
                repository.save(entity);
            }
            return entity;
        }).orElseGet(() -> {
            ImsiSyncConfigEntity entity = new ImsiSyncConfigEntity();
            entity.setId(CONFIG_ID);
            entity.setIntervalSeconds(DEFAULT_INTERVAL_SECONDS);
            entity.setBatchSize(DEFAULT_BATCH_SIZE);
            entity.setMaxFiles(DEFAULT_MAX_FILES);
            entity.setDeviceFilter(DEFAULT_DEVICE_FILTER);
            entity.setCreatedAt(Instant.now());
            entity.setUpdatedAt(Instant.now());
            return repository.save(entity);
        });
    }

    @Transactional
    public ImsiSyncConfigEntity updateConfig(ImsiSyncConfigEntity incoming) {
        ImsiSyncConfigEntity entity = repository.findById(CONFIG_ID)
                .orElseGet(() -> {
                    ImsiSyncConfigEntity created = new ImsiSyncConfigEntity();
                    created.setId(CONFIG_ID);
                    created.setCreatedAt(Instant.now());
                    return created;
                });

        entity.setFtpHost(trimToNull(incoming.getFtpHost()));
        entity.setFtpPort(normalizePort(incoming.getFtpPort()));
        entity.setFtpUser(trimToNull(incoming.getFtpUser()));
        entity.setFtpPass(incoming.getFtpPass());
        entity.setFtpDirectory(trimToNull(incoming.getFtpDirectory()));
        entity.setIntervalSeconds(normalizeInterval(incoming.getIntervalSeconds()));
        entity.setBatchSize(normalizeBatchSize(incoming.getBatchSize()));
        entity.setMaxFiles(normalizeMaxFiles(incoming.getMaxFiles()));
        String normalizedFilter;
        if (incoming.getDeviceFilter() == null) {
            normalizedFilter = entity.getDeviceFilter();
            if (normalizedFilter == null) {
                normalizedFilter = DEFAULT_DEVICE_FILTER;
            }
        } else {
            normalizedFilter = normalizeDeviceFilter(incoming.getDeviceFilter());
        }
        entity.setDeviceFilter(normalizedFilter);
        entity.setUpdatedAt(Instant.now());
        return repository.save(entity);
    }

    @Transactional
    public void recordSyncResult(boolean ok, String message, int records, long elapsedMs, Instant timestamp) {
        ImsiSyncConfigEntity entity = repository.findById(CONFIG_ID)
                .orElseGet(this::getOrCreateConfig);
        entity.setLastSyncOk(ok);
        entity.setLastSyncMessage(trimToNull(message));
        entity.setLastSyncRecords(records);
        entity.setLastSyncElapsedMs(elapsedMs);
        entity.setLastSyncTime(timestamp);
        entity.setUpdatedAt(Instant.now());
        repository.save(entity);
    }

    @Transactional
    public void applySettings(SettingsConfig settings) {
        if (settings == null) {
            return;
        }
        ImsiSyncConfigEntity incoming = new ImsiSyncConfigEntity();
        incoming.setFtpHost(trimToNull(settings.getImsiFtpHost()));
        incoming.setFtpPort(normalizePort(settings.getImsiFtpPort()));
        incoming.setFtpUser(trimToNull(settings.getImsiFtpUser()));
        incoming.setFtpPass(settings.getImsiFtpPass());
        incoming.setIntervalSeconds(normalizeInterval(settings.getImsiSyncInterval()));
        incoming.setBatchSize(normalizeBatchSize(settings.getImsiSyncBatchSize()));
        incoming.setMaxFiles(normalizeMaxFiles(settings.getImsiSyncMaxFiles()));
        incoming.setDeviceFilter(settings.getImsiDeviceFilter());
        updateConfig(incoming);
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private Integer normalizePort(Integer port) {
        if (port == null) {
            return 21;
        }
        if (port <= 0 || port > 65535) {
            return 21;
        }
        return port;
    }

    private Integer normalizeInterval(Integer interval) {
        if (interval == null || interval <= 0) {
            return DEFAULT_INTERVAL_SECONDS;
        }
        return Math.min(interval, 24 * 3600);
    }

    private Integer normalizeBatchSize(Integer batchSize) {
        if (batchSize == null || batchSize <= 0) {
            return DEFAULT_BATCH_SIZE;
        }
        return Math.min(batchSize, 20000);
    }

    private Integer normalizeMaxFiles(Integer maxFiles) {
        if (maxFiles == null || maxFiles <= 0) {
            return DEFAULT_MAX_FILES;
        }
        return Math.min(maxFiles, 50);
    }

    private String normalizeDeviceFilter(String filter) {
        if (filter == null) {
            return null;
        }
        String trimmed = filter.trim();
        if (trimmed.length() > 255) {
            trimmed = trimmed.substring(0, 255);
        }
        return trimmed;
    }
}
