package com.example.nvr;

import com.example.nvr.persistence.EventStorageService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/nvr/alerts")
public class AlertPushController {

    private final EventStorageService eventStorageService;
    private final AlertEventParser alertEventParser;

    private static volatile long lastReceivedAt = 0L;
    private static volatile int lastBytes = 0;

    public AlertPushController(EventStorageService eventStorageService,
                               AlertEventParser alertEventParser) {
        this.eventStorageService = eventStorageService;
        this.alertEventParser = alertEventParser;
    }

    @PostMapping(value = "/push", consumes = { MediaType.ALL_VALUE })
    public ResponseEntity<Map<String, Object>> receive(@RequestBody byte[] body) {
        String s = new String(body, StandardCharsets.UTF_8);
        Map<String, Object> ev = alertEventParser.parse(s);
        boolean alertSaved = false;
        boolean cameraSaved = false;
        try {
            alertSaved = eventStorageService.recordAlertEvent(ev, s);
            cameraSaved = eventStorageService.recordCameraAlarm(ev, s);
        } catch (Exception ignored) {
        }
        if (alertSaved || cameraSaved) {
            AlertHub.broadcast(ev);
        }
        Map<String, Object> out = new HashMap<>();
        out.put("ok", true);
        out.put("received", body.length);
        lastReceivedAt = System.currentTimeMillis();
        lastBytes = body.length;
        return ResponseEntity.ok(out);
    }

    @GetMapping(path = "/subscribe", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Object subscribe() {
        return AlertHub.subscribe();
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        Map<String, Object> m = new HashMap<>();
        m.put("ok", true);
        m.put("lastReceivedAt", lastReceivedAt);
        m.put("lastBytes", lastBytes);
        return m;
    }
}
