package com.example.nvr.persistence;

import javax.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "alert_events")
public class AlertEventEntity {

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

    @Column(name = "status", length = 32)
    private String status = "未处理";

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    public AlertEventEntity() {
    }

    public AlertEventEntity(String eventId, String eventType, String camChannel,
                            String level, String eventTime, String status) {
        this.eventId = eventId;
        this.eventType = eventType;
        this.camChannel = camChannel;
        this.level = level;
        this.eventTime = eventTime;
        this.status = (status == null || status.isBlank()) ? "未处理" : status.trim();
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

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = (status == null || status.isBlank()) ? "未处理" : status.trim();
    }

    public void setCamChannel(String camChannel) {
        this.camChannel = camChannel;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
