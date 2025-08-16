package com.example.nvr;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/api/nvr/alerts")
public class AlertPushController {

    private static volatile long lastReceivedAt = 0L;
    private static volatile int lastBytes = 0;

    @PostMapping(value = "/push", consumes = { MediaType.ALL_VALUE })
    public ResponseEntity<Map<String, Object>> receive(@RequestBody byte[] body) {
        String s = new String(body, StandardCharsets.UTF_8);
        Map<String, Object> ev = parseEvent(s);
        AlertHub.broadcast(ev);
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

    private Map<String, Object> parseEvent(String raw) {
        Map<String, Object> m = new HashMap<>();
        m.put("type", "alert");
        m.put("id", UUID.randomUUID().toString());
        m.put("raw", raw);

        // Try to parse Hikvision XML <EventNotificationAlert>...
        String eventType = text(raw, "eventType");
        String channelStr = text(raw, "channelID");
        Integer channel = null;
        try { if (channelStr != null && !channelStr.isEmpty()) channel = Integer.parseInt(channelStr.trim()); } catch (NumberFormatException ignored) {}
        Integer port = null;
        if (channel != null) { port = channel >= 100 ? channel / 100 : channel; }
        String time = text(raw, "dateTime");
        if (time == null) time = text(raw, "startTime");

        m.put("eventType", eventType);
        m.put("channelID", channel);
        m.put("port", port);
        if (time != null) m.put("time", time);
        String level = "minor";
        if (eventType != null) {
            String et = eventType.toLowerCase();
            if (et.contains("intrusion") || et.contains("field") || et.contains("line")) level = "major";
            if (et.contains("tamper") || et.contains("threat")) level = "critical";
        }
        m.put("level", level);
        return m;
    }

    private String text(String xml, String tag) {
        Pattern p = Pattern.compile("<" + tag + ">\\s*(.*?)\\s*</" + tag + ">", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
        Matcher m = p.matcher(xml);
        if (m.find()) return m.group(1);
        return null;
    }
}
