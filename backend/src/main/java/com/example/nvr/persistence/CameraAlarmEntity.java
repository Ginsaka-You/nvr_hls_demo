package com.example.nvr.persistence;

import javax.persistence.*;
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

    @Column(name = "channel_id")
    private Integer channelId;

    @Column(name = "port")
    private Integer port;

    @Column(name = "level", length = 32)
    private String level;

    @Column(name = "event_time", length = 64)
    private String eventTime;

    @Lob
    @Column(name = "raw_payload")
    private String rawPayload;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    public CameraAlarmEntity() {
    }

    public CameraAlarmEntity(String eventId, String eventType, Integer channelId, Integer port,
                             String level, String eventTime, String rawPayload) {
        this.eventId = eventId;
        this.eventType = eventType;
        this.channelId = channelId;
        this.port = port;
        this.level = level;
        this.eventTime = eventTime;
        this.rawPayload = rawPayload;
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

    public Integer getChannelId() {
        return channelId;
    }

    public Integer getPort() {
        return port;
    }

    public String getLevel() {
        return level;
    }

    public String getEventTime() {
        return eventTime;
    }

    public String getRawPayload() {
        return rawPayload;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
