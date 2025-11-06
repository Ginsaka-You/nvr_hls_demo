package com.example.nvr;

import java.util.Map;
import java.util.Set;

/**
 * Centralized channel filter so that specific camera channels (e.g. 001) can be
 * ignored consistently across ingestion, persistence, and streaming paths.
 */
public final class CameraChannelBlocklist {

    private static final Set<Integer> BLOCKED_PHYSICAL_CHANNELS = Set.of(1);
    private static final Set<Integer> BLOCKED_RAW_CHANNEL_IDS = Set.of(0);

    private CameraChannelBlocklist() {
    }

    public static boolean shouldIgnore(Map<String, Object> event) {
        if (event == null) {
            return false;
        }
        String camChannel = normalizeText(event.get("camChannel"));
        Integer channelId = parseInteger(event.get("channelID"));
        if (channelId == null) {
            channelId = parseInteger(event.get("channel"));
        }
        if (channelId == null) {
            channelId = parseInteger(event.get("cameraID"));
        }
        if (channelId == null) {
            channelId = parseInteger(event.get("dynChannelID"));
        }
        Integer port = parseInteger(event.get("port"));
        if (port == null) {
            port = parseInteger(event.get("inputPort"));
        }
        if (channelId != null && BLOCKED_RAW_CHANNEL_IDS.contains(channelId)) {
            return true;
        }
        return shouldIgnore(channelId, port, camChannel);
    }

    public static boolean shouldIgnore(Integer channelId, Integer port, String camChannel) {
        if (channelId != null && BLOCKED_RAW_CHANNEL_IDS.contains(channelId)) {
            return true;
        }
        Integer physical = resolvePhysicalChannel(channelId, port, camChannel);
        return physical != null && BLOCKED_PHYSICAL_CHANNELS.contains(physical);
    }

    private static Integer resolvePhysicalChannel(Integer channelId, Integer port, String camChannel) {
        Integer fromCam = parseCamChannel(camChannel);
        if (fromCam != null) {
            return fromCam;
        }
        Integer fromChannelId = normalizeChannelId(channelId);
        if (fromChannelId != null) {
            return fromChannelId;
        }
        return normalizePort(port);
    }

    private static Integer parseCamChannel(String camChannel) {
        String text = normalizeText(camChannel);
        if (text == null) {
            return null;
        }
        if (!text.chars().allMatch(Character::isDigit)) {
            return null;
        }
        if (text.length() <= 3) {
            if (text.length() > 2) {
                Integer physical = parsePositiveInt(text.substring(0, text.length() - 2));
                if (physical != null) {
                    return physical;
                }
            }
            return parsePositiveInt(text);
        }
        return parsePositiveInt(text.substring(0, text.length() - 2));
    }

    private static Integer normalizeChannelId(Integer channelId) {
        if (channelId == null || channelId <= 0) {
            return null;
        }
        if (channelId > 32) {
            return ((channelId - 1) % 32) + 1;
        }
        return channelId;
    }

    private static Integer normalizePort(Integer port) {
        if (port == null || port <= 0) {
            return null;
        }
        return port;
    }

    private static Integer parseInteger(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number) {
            int v = ((Number) value).intValue();
            return v >= 0 ? v : null;
        }
        String text = normalizeText(value);
        if (text == null) {
            return null;
        }
        try {
            int parsed = Integer.parseInt(text);
            return parsed >= 0 ? parsed : null;
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static Integer parsePositiveInt(String digits) {
        if (digits == null || digits.isBlank()) {
            return null;
        }
        try {
            int parsed = Integer.parseInt(digits);
            return parsed > 0 ? parsed : null;
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static String normalizeText(Object value) {
        if (value == null) {
            return null;
        }
        String text = value.toString().trim();
        return text.isEmpty() ? null : text;
    }
}
