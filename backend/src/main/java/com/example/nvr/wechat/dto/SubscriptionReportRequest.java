package com.example.nvr.wechat.dto;

import java.util.Map;

public class SubscriptionReportRequest {
    private String code;
    private Map<String, String> results;
    private String scene;

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public Map<String, String> getResults() {
        return results;
    }

    public void setResults(Map<String, String> results) {
        this.results = results;
    }

    public String getScene() {
        return scene;
    }

    public void setScene(String scene) {
        this.scene = scene;
    }
}

