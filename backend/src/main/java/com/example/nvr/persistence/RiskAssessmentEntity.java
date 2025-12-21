package com.example.nvr.persistence;

import javax.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "risk_assessments",
        indexes = {
                @Index(name = "idx_risk_updated_at", columnList = "updated_at"),
                @Index(name = "idx_risk_action_type", columnList = "action_type")
        })
public class RiskAssessmentEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "classification", nullable = false, length = 32)
    private String classification;

    @Column(name = "action_type", length = 8)
    private String actionType;

    @Column(name = "score")
    private Integer score;

    @Column(name = "summary", length = 255)
    private String summary;

    @Column(name = "details_json", columnDefinition = "text")
    private String detailsJson;

    @Column(name = "status", length = 16)
    private String status;

    @Column(name = "snapshot_path", length = 512)
    private String snapshotPath;

    @Column(name = "radar_track_summary", columnDefinition = "text")
    private String radarTrackSummary;

    @Column(name = "window_start")
    private Instant windowStart;

    @Column(name = "window_end")
    private Instant windowEnd;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @Column(name = "remote_alarm_gate_triggered")
    private Boolean remoteAlarmGateTriggered;

    @Column(name = "sound_light_triggered")
    private Boolean soundLightTriggered;

    public RiskAssessmentEntity() {
    }

    public Long getId() {
        return id;
    }

    public String getClassification() {
        return classification;
    }

    public void setClassification(String classification) {
        this.classification = classification;
    }

    public String getActionType() {
        return actionType;
    }

    public void setActionType(String actionType) {
        this.actionType = actionType;
    }

    public Integer getScore() {
        return score;
    }

    public void setScore(Integer score) {
        this.score = score;
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    public String getDetailsJson() {
        return detailsJson;
    }

    public void setDetailsJson(String detailsJson) {
        this.detailsJson = detailsJson;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getSnapshotPath() {
        return snapshotPath;
    }

    public void setSnapshotPath(String snapshotPath) {
        this.snapshotPath = snapshotPath;
    }

    public String getRadarTrackSummary() {
        return radarTrackSummary;
    }

    public void setRadarTrackSummary(String radarTrackSummary) {
        this.radarTrackSummary = radarTrackSummary;
    }

    public Instant getWindowStart() {
        return windowStart;
    }

    public void setWindowStart(Instant windowStart) {
        this.windowStart = windowStart;
    }

    public Instant getWindowEnd() {
        return windowEnd;
    }

    public void setWindowEnd(Instant windowEnd) {
        this.windowEnd = windowEnd;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    public Boolean getRemoteAlarmGateTriggered() {
        return remoteAlarmGateTriggered;
    }

    public void setRemoteAlarmGateTriggered(Boolean remoteAlarmGateTriggered) {
        this.remoteAlarmGateTriggered = remoteAlarmGateTriggered;
    }

    public Boolean getSoundLightTriggered() {
        return soundLightTriggered;
    }

    public void setSoundLightTriggered(Boolean soundLightTriggered) {
        this.soundLightTriggered = soundLightTriggered;
    }
}
