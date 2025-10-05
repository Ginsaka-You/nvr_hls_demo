package com.example.nvr;

import com.example.nvr.persistence.EventStorageService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/alerts")
public class AlertManualController {

    private final EventStorageService eventStorageService;

    public AlertManualController(EventStorageService eventStorageService) {
        this.eventStorageService = eventStorageService;
    }

    @PostMapping("/manual")
    public ResponseEntity<Map<String, Object>> manual(@RequestBody ManualAlertRequest request) {
        if (request == null) request = new ManualAlertRequest();
        String eventId = request.getEventId();
        if (eventId == null || eventId.isBlank()) {
            eventId = "manual-" + Instant.now().toEpochMilli();
        }
        String eventType = request.getEventType() != null ? request.getEventType() : "manual";
        String level = request.getLevel() != null ? request.getLevel() : "major";
        String eventTime = request.getEventTime();
        if (eventTime == null || eventTime.isBlank()) {
            eventTime = DateTimeFormatter.ISO_INSTANT.format(Instant.now());
        }
        String payload = request.getPayload();
        if (payload == null && request.getData() != null) {
            payload = request.getData();
        }
        eventStorageService.recordManualAlert(eventId, eventType, request.getChannelId(), request.getPort(), level, eventTime);
        Map<String, Object> out = new HashMap<>();
        out.put("ok", true);
        out.put("eventId", eventId);
        return ResponseEntity.ok(out);
    }

    public static class ManualAlertRequest {
        private String eventId;
        private String eventType;
        private Integer channelId;
        private Integer port;
        private String level;
        private String eventTime;
        private String payload;
        private String data;

        public String getEventId() {
            return eventId;
        }

        public void setEventId(String eventId) {
            this.eventId = eventId;
        }

        public String getEventType() {
            return eventType;
        }

        public void setEventType(String eventType) {
            this.eventType = eventType;
        }

        public Integer getChannelId() {
            return channelId;
        }

        public void setChannelId(Integer channelId) {
            this.channelId = channelId;
        }

        public Integer getPort() {
            return port;
        }

        public void setPort(Integer port) {
            this.port = port;
        }

        public String getLevel() {
            return level;
        }

        public void setLevel(String level) {
            this.level = level;
        }

        public String getEventTime() {
            return eventTime;
        }

        public void setEventTime(String eventTime) {
            this.eventTime = eventTime;
        }

        public String getPayload() {
            return payload;
        }

        public void setPayload(String payload) {
            this.payload = payload;
        }

        public String getData() {
            return data;
        }

        public void setData(String data) {
            this.data = data;
        }
    }
}
