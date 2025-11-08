package com.example.nvr.tcb.model;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Pattern;

public class AlertEvent {

    @NotBlank
    private String userId;

    @NotBlank
    private String title;

    @NotBlank
    @Pattern(regexp = "CRITICAL|HIGH|MEDIUM|LOW")
    private String severity;

    @NotBlank
    private String location;

    @NotBlank
    private String device;

    private String camera;

    @NotBlank
    private String occurAt;

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getSeverity() {
        return severity;
    }

    public void setSeverity(String severity) {
        this.severity = severity;
    }

    public String getLocation() {
        return location;
    }

    public void setLocation(String location) {
        this.location = location;
    }

    public String getDevice() {
        return device;
    }

    public void setDevice(String device) {
        this.device = device;
    }

    public String getCamera() {
        return camera;
    }

    public void setCamera(String camera) {
        this.camera = camera;
    }

    public String getOccurAt() {
        return occurAt;
    }

    public void setOccurAt(String occurAt) {
        this.occurAt = occurAt;
    }
}

