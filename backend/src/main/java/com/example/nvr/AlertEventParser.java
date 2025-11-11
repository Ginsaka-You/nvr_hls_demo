package com.example.nvr;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class AlertEventParser {

    public Map<String, Object> parse(String payload) {
        Map<String, Object> event = new HashMap<>();
        event.put("type", "alert");
        if (payload != null) {
            event.put("raw", payload);
        }
        applyXmlHints(event, payload);
        event.put("id", computeEventId(event, payload));
        return event;
    }

    private void applyXmlHints(Map<String, Object> event, String xml) {
        if (xml == null) {
            return;
        }
        String eventType = firstValue(xml, "eventType", "event", "eventname");
        Integer channel = parseInteger(firstValue(xml, "channelID", "videoChannelID", "cameraID", "channel", "dynChannelID"));
        Integer dynChannel = parseInteger(text(xml, "dynChannelID"));
        Integer port = parseInteger(firstValue(xml, "port", "portID", "inputPort"));
        if (channel == null && dynChannel != null) {
            channel = dynChannel;
        }
        if (port == null) {
            if (channel != null) {
                port = channel >= 100 ? channel / 100 : channel;
            } else if (dynChannel != null) {
                port = dynChannel;
            }
        }
        String time = firstValue(xml, "dateTime", "startTime", "triggerTime", "eventTime");
        String status = firstValue(xml, "eventState", "status");

        if (eventType != null && !eventType.isBlank()) {
            event.put("eventType", eventType);
        }
        if (channel != null) {
            event.put("channelID", channel);
        }
        if (port != null) {
            event.put("port", port);
        }
        String camChannel = normalizeStreamSuffix(deriveCamChannel(channel, port));
        if (camChannel != null) {
            event.put("camChannel", camChannel);
        }
        if (time != null) {
            event.put("time", time);
        }
        if (status != null) {
            event.put("status", status);
        }

        String level = "minor";
        if (eventType != null) {
            String et = eventType.toLowerCase();
            if (et.contains("intrusion") || et.contains("field") || et.contains("line")) level = "major";
            if (et.contains("tamper") || et.contains("threat")) level = "critical";
            if (et.contains("loiter")) level = "major";
        }
        event.put("level", level);
    }

    private String firstValue(String xml, String... tags) {
        if (xml == null || tags == null) {
            return null;
        }
        for (String tag : tags) {
            if (tag == null || tag.isBlank()) continue;
            String value = text(xml, tag);
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private String text(String xml, String tag) {
        Pattern p = Pattern.compile("<" + tag + "[^>]*>\\s*(.*?)\\s*</" + tag + ">", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
        Matcher m = p.matcher(xml);
        if (m.find()) {
            String value = m.group(1);
            if (value != null) {
                String trimmed = value.trim();
                if (trimmed.startsWith("<![CDATA[") && trimmed.endsWith("]]>")) {
                    trimmed = trimmed.substring(9, trimmed.length() - 3).trim();
                }
                return trimmed;
            }
        }
        return null;
    }

    private Integer parseInteger(String value) {
        if (value == null) {
            return null;
        }
        try {
            String trimmed = value.trim();
            if (trimmed.isEmpty()) {
                return null;
            }
            return Integer.parseInt(trimmed);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private String deriveCamChannel(Integer channelId, Integer port) {
        Integer source = channelId != null && channelId > 0 ? channelId : port;
        if (source == null || source <= 0) {
            return null;
        }
        int physical = source;
        int stream = 1;
        if (source >= 100) {
            int prefix = source / 100;
            int suffix = source % 100;
            if (prefix > 0) {
                physical = prefix;
            }
            if (suffix > 0) {
                stream = suffix;
            }
        } else if (source > 32) {
            physical = ((source - 1) % 32) + 1;
            stream = ((source - 1) / 32) + 1;
        }
        return String.format(Locale.ROOT, "%d%02d", physical, Math.max(1, stream));
    }

    private String normalizeStreamSuffix(String channel) {
        if (channel == null) {
            return null;
        }
        String trimmed = channel.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (trimmed.endsWith("*02")) {
            return trimmed.substring(0, trimmed.length() - 3) + "*01";
        }
        if (trimmed.matches("\\d{3,}")) {
            String prefix = trimmed.substring(0, trimmed.length() - 2);
            String suffix = trimmed.substring(trimmed.length() - 2);
            if (!suffix.equals("01") && suffix.chars().allMatch(Character::isDigit)) {
                return prefix + "01";
            }
        }
        return trimmed;
    }

    private String computeEventId(Map<String, Object> event, String payload) {
        String eventType = normalizeComponent(event.get("eventType"));
        String camChannel = normalizeComponent(event.get("camChannel"));
        String status = normalizeComponent(event.get("status"));
        String time = normalizeComponent(event.get("time"));
        String signature = String.join("|", eventType, camChannel, status, time);
        if (!signature.replace("|", "").isBlank()) {
            return "evt-" + md5Hex(signature);
        }
        if (payload != null && !payload.isBlank()) {
            return "evt-" + md5Hex(payload);
        }
        return "evt-" + UUID.randomUUID();
    }

    private String normalizeComponent(Object value) {
        if (value == null) {
            return "";
        }
        String s = value.toString().trim();
        return s.isEmpty() ? "" : s.toLowerCase(Locale.ROOT);
    }

    private String md5Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            return UUID.randomUUID().toString();
        }
    }
}
