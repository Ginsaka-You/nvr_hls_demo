package com.example.nvr.imsi.dto;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public class FtpTestResponse {
    private final boolean ok;
    private final String message;
    private final Map<String, Object> details;
    private final long elapsedMs;
    private final Instant timestamp = Instant.now();

    private FtpTestResponse(boolean ok, String message, Map<String, Object> details, long elapsedMs) {
        this.ok = ok;
        this.message = message;
        this.details = sanitizeDetails(details);
        this.elapsedMs = elapsedMs;
    }

    public static FtpTestResponse success(String message, Map<String, Object> details, long startMillis) {
        long elapsed = Math.max(0, System.currentTimeMillis() - startMillis);
        return new FtpTestResponse(true, message, details, elapsed);
    }

    public static FtpTestResponse error(String message) {
        return new FtpTestResponse(false, message, Map.of(), 0);
    }

    public static FtpTestResponse error(String message, Map<String, Object> details, long startMillis) {
        long elapsed = Math.max(0, System.currentTimeMillis() - startMillis);
        return new FtpTestResponse(false, message, details, elapsed);
    }

    private static Map<String, Object> sanitizeDetails(Map<String, Object> details) {
        if (details == null || details.isEmpty()) {
            return Map.of();
        }
        LinkedHashMap<String, Object> cleaned = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : details.entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null) {
                cleaned.put(entry.getKey(), entry.getValue());
            }
        }
        return cleaned.isEmpty() ? Map.of() : Collections.unmodifiableMap(cleaned);
    }

    public boolean isOk() {
        return ok;
    }

    public String getMessage() {
        return message;
    }

    public Map<String, Object> getDetails() {
        return details;
    }

    public long getElapsedMs() {
        return elapsedMs;
    }

    public Instant getTimestamp() {
        return timestamp;
    }
}
