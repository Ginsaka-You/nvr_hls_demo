package com.example.nvr.persistence;

import javax.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "imsi_sync_config")
public class ImsiSyncConfigEntity {

    @Id
    private Long id;

    @Column(name = "ftp_host", length = 128)
    private String ftpHost;

    @Column(name = "ftp_port")
    private Integer ftpPort;

    @Column(name = "ftp_user", length = 128)
    private String ftpUser;

    @Column(name = "ftp_pass", length = 255)
    private String ftpPass;

    @Column(name = "ftp_directory", length = 255)
    private String ftpDirectory;

    @Column(name = "interval_seconds")
    private Integer intervalSeconds;

    @Column(name = "batch_size")
    private Integer batchSize;

    @Column(name = "max_files")
    private Integer maxFiles;

    @Column(name = "device_filter", length = 255)
    private String deviceFilter;

    @Column(name = "last_sync_ok")
    private Boolean lastSyncOk;

    @Column(name = "last_sync_message", length = 255)
    private String lastSyncMessage;

    @Column(name = "last_sync_records")
    private Integer lastSyncRecords;

    @Column(name = "last_sync_elapsed_ms")
    private Long lastSyncElapsedMs;

    @Column(name = "last_sync_time")
    private Instant lastSyncTime;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    public ImsiSyncConfigEntity() {
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getFtpHost() {
        return ftpHost;
    }

    public void setFtpHost(String ftpHost) {
        this.ftpHost = ftpHost;
    }

    public Integer getFtpPort() {
        return ftpPort;
    }

    public void setFtpPort(Integer ftpPort) {
        this.ftpPort = ftpPort;
    }

    public String getFtpUser() {
        return ftpUser;
    }

    public void setFtpUser(String ftpUser) {
        this.ftpUser = ftpUser;
    }

    public String getFtpPass() {
        return ftpPass;
    }

    public void setFtpPass(String ftpPass) {
        this.ftpPass = ftpPass;
    }

    public String getFtpDirectory() {
        return ftpDirectory;
    }

    public void setFtpDirectory(String ftpDirectory) {
        this.ftpDirectory = ftpDirectory;
    }

    public Integer getIntervalSeconds() {
        return intervalSeconds;
    }

    public void setIntervalSeconds(Integer intervalSeconds) {
        this.intervalSeconds = intervalSeconds;
    }

    public Integer getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(Integer batchSize) {
        this.batchSize = batchSize;
    }

    public Integer getMaxFiles() {
        return maxFiles;
    }

    public void setMaxFiles(Integer maxFiles) {
        this.maxFiles = maxFiles;
    }

    public String getDeviceFilter() {
        return deviceFilter;
    }

    public void setDeviceFilter(String deviceFilter) {
        this.deviceFilter = deviceFilter;
    }

    public Boolean getLastSyncOk() {
        return lastSyncOk;
    }

    public void setLastSyncOk(Boolean lastSyncOk) {
        this.lastSyncOk = lastSyncOk;
    }

    public String getLastSyncMessage() {
        return lastSyncMessage;
    }

    public void setLastSyncMessage(String lastSyncMessage) {
        this.lastSyncMessage = lastSyncMessage;
    }

    public Integer getLastSyncRecords() {
        return lastSyncRecords;
    }

    public void setLastSyncRecords(Integer lastSyncRecords) {
        this.lastSyncRecords = lastSyncRecords;
    }

    public Long getLastSyncElapsedMs() {
        return lastSyncElapsedMs;
    }

    public void setLastSyncElapsedMs(Long lastSyncElapsedMs) {
        this.lastSyncElapsedMs = lastSyncElapsedMs;
    }

    public Instant getLastSyncTime() {
        return lastSyncTime;
    }

    public void setLastSyncTime(Instant lastSyncTime) {
        this.lastSyncTime = lastSyncTime;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
