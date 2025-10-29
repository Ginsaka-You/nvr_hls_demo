package com.example.nvr.persistence;

import javax.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "risk_assessments",
        indexes = {
                @Index(name = "idx_risk_subject", columnList = "subject_type, subject_key"),
                @Index(name = "idx_risk_updated_at", columnList = "updated_at")
        })
public class RiskAssessmentEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "subject_type", nullable = false, length = 32)
    private String subjectType;

    @Column(name = "subject_key", nullable = false, length = 128)
    private String subjectKey;

    @Column(name = "classification", nullable = false, length = 32)
    private String classification;

    @Column(name = "score")
    private Integer score;

    @Column(name = "summary", length = 255)
    private String summary;

    @Lob
    @Column(name = "details_json")
    private String detailsJson;

    @Column(name = "window_start")
    private Instant windowStart;

    @Column(name = "window_end")
    private Instant windowEnd;

    @Column(name = "updated_at")
    private Instant updatedAt;

    public RiskAssessmentEntity() {
    }

    public RiskAssessmentEntity(String subjectType, String subjectKey) {
        this.subjectType = subjectType;
        this.subjectKey = subjectKey;
    }

    public Long getId() {
        return id;
    }

    public String getSubjectType() {
        return subjectType;
    }

    public void setSubjectType(String subjectType) {
        this.subjectType = subjectType;
    }

    public String getSubjectKey() {
        return subjectKey;
    }

    public void setSubjectKey(String subjectKey) {
        this.subjectKey = subjectKey;
    }

    public String getClassification() {
        return classification;
    }

    public void setClassification(String classification) {
        this.classification = classification;
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
}
