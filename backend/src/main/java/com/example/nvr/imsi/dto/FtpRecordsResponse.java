package com.example.nvr.imsi.dto;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class FtpRecordsResponse {
    private final boolean ok;
    private final String message;
    private final List<ImsiRecordView> records;
    private final List<String> sourceFiles;
    private final Map<String, Object> details;
    private final long elapsedMs;
    private final Instant timestamp = Instant.now();

    private FtpRecordsResponse(boolean ok,
                               String message,
                               List<ImsiRecordView> records,
                               List<String> sourceFiles,
                               Map<String, Object> details,
                               long elapsedMs) {
        this.ok = ok;
        this.message = message;
        this.records = records == null ? Collections.emptyList() : List.copyOf(records);
        this.sourceFiles = sourceFiles == null ? Collections.emptyList() : List.copyOf(sourceFiles);
        this.details = sanitizeDetails(details);
        this.elapsedMs = elapsedMs;
    }

    public static FtpRecordsResponse success(String message,
                                             Map<String, Object> details,
                                             List<ImsiRecordView> records,
                                             List<String> sourceFiles,
                                             long startMillis) {
        long elapsed = Math.max(0, System.currentTimeMillis() - startMillis);
        return new FtpRecordsResponse(true, message, records, sourceFiles, details, elapsed);
    }

    public static FtpRecordsResponse error(String message) {
        return new FtpRecordsResponse(false, message, Collections.emptyList(), Collections.emptyList(), Map.of(), 0);
    }

    public static FtpRecordsResponse error(String message,
                                           Map<String, Object> details,
                                           List<ImsiRecordView> records,
                                           List<String> sourceFiles,
                                           long startMillis) {
        long elapsed = Math.max(0, System.currentTimeMillis() - startMillis);
        return new FtpRecordsResponse(false, message, records, sourceFiles, details, elapsed);
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

    public List<ImsiRecordView> getRecords() {
        return records;
    }

    public List<String> getSourceFiles() {
        return sourceFiles;
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
