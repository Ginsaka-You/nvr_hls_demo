package com.example.nvr.persistence;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;

import javax.persistence.*;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

@Entity
@Table(name = "camera_alarms")
public class CameraAlarmEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_id", length = 64, nullable = false)
    private String eventId;

    @Column(name = "event_type", length = 128)
    private String eventType;

    @Column(name = "cam_channel", length = 32)
    private String camChannel;

    @Column(name = "level", length = 32)
    private String level;

    @Column(name = "event_time", length = 64)
    private String eventTime;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "snapshot_path", length = 512)
    private String snapshotPath;

    public CameraAlarmEntity() {
    }

    public CameraAlarmEntity(String eventId, String eventType, String camChannel,
                             String level, String eventTime) {
        this.eventId = eventId;
        this.eventType = eventType;
        this.camChannel = camChannel;
        this.level = level;
        this.eventTime = eventTime;
    }

    public Long getId() {
        return id;
    }

    public String getEventId() {
        return eventId;
    }

    public String getEventType() {
        return eventType;
    }

    public String getCamChannel() {
        return camChannel;
    }

    public String getLevel() {
        return level;
    }

    public String getEventTime() {
        return eventTime;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    @JsonIgnore
    public String getSnapshotPath() {
        return snapshotPath;
    }

    public void setSnapshotPath(String snapshotPath) {
        this.snapshotPath = snapshotPath;
    }

    @Transient
    @JsonProperty("snapshotUrl")
    public String getSnapshotUrl() {
        if (snapshotPath == null || snapshotPath.isBlank()) {
            return null;
        }
        String normalized = snapshotPath.replace('\\', '/');
        String[] parts = normalized.split("/");
        if (parts.length < 3) {
            return "/api/evidence/snapshots/" + normalized;
        }
        String channel = parts[0];
        String date = parts[1];
        String filename = parts[parts.length - 1];
        try {
            String encodedFilename = URLEncoder.encode(filename, StandardCharsets.UTF_8);
            return "/api/evidence/snapshots/" + channel + "/" + date + "/" + encodedFilename;
        } catch (Exception ex) {
            return "/api/evidence/snapshots/" + channel + "/" + date + "/" + filename;
        }
    }
}
