package com.example.nvr.persistence;

import com.example.nvr.RadarController;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class EventStorageService {

    private static final Logger log = LoggerFactory.getLogger(EventStorageService.class);

    private final AlertEventRepository alertEventRepository;
    private final CameraAlarmRepository cameraAlarmRepository;
    private final RadarTargetRepository radarTargetRepository;

    public EventStorageService(AlertEventRepository alertEventRepository,
                               CameraAlarmRepository cameraAlarmRepository,
                               RadarTargetRepository radarTargetRepository) {
        this.alertEventRepository = alertEventRepository;
        this.cameraAlarmRepository = cameraAlarmRepository;
        this.radarTargetRepository = radarTargetRepository;
    }

    @Transactional
    public void recordAlertEvent(Map<String, Object> event, String rawPayload) {
        try {
            String eventId = stringValue(event.get("id"));
            if (eventId == null) {
                eventId = "evt-" + Instant.now().toEpochMilli();
            }
            String eventType = stringValue(event.get("eventType"));
            Integer channelId = intValue(event.get("channelID"));
            Integer port = intValue(event.get("port"));
            String level = stringValue(event.get("level"));
            String eventTime = stringValue(event.get("time"));

            AlertEventEntity entity = new AlertEventEntity(eventId, eventType, channelId, port, level, eventTime, rawPayload);
            alertEventRepository.save(entity);
        } catch (Exception ex) {
            log.warn("Failed to persist alert event", ex);
        }
    }

    @Transactional
    public void recordCameraAlarm(Map<String, Object> event, String rawPayload) {
        try {
            Integer port = intValue(event.get("port"));
            if (port == null) {
                return; // Not a camera-specific alert
            }
            String eventId = stringValue(event.get("id"));
            if (eventId == null) {
                eventId = "cam-" + Instant.now().toEpochMilli();
            }
            String eventType = stringValue(event.get("eventType"));
            Integer channelId = intValue(event.get("channelID"));
            String level = stringValue(event.get("level"));
            String eventTime = stringValue(event.get("time"));

            CameraAlarmEntity entity = new CameraAlarmEntity(eventId, eventType, channelId, port, level, eventTime, rawPayload);
            cameraAlarmRepository.save(entity);
        } catch (Exception ex) {
            log.warn("Failed to persist camera alarm", ex);
        }
    }

    @Transactional
    public void recordRadarTargets(RadarController.RadarTargetsResponse response) {
        if (response == null || !response.isOk()) {
            return;
        }
        List<RadarController.RadarTargetDto> targets = response.getTargets();
        if (targets == null || targets.isEmpty()) {
            return;
        }
        try {
            Instant capturedAt = response.getTimestamp();
            List<RadarTargetEntity> entities = new ArrayList<>(targets.size());
            for (RadarController.RadarTargetDto dto : targets) {
                RadarTargetEntity entity = new RadarTargetEntity(
                        response.getHost(),
                        response.getControlPort(),
                        response.getDataPort(),
                        response.getActualDataPort(),
                        response.isTcp(),
                        response.getStatus(),
                        response.getPayloadLength(),
                        response.getTargetCount(),
                        dto.getId(),
                        dto.getLongitudinalDistance(),
                        dto.getLateralDistance(),
                        dto.getSpeed(),
                        dto.getRange(),
                        dto.getAngle(),
                        dto.getAmplitude(),
                        dto.getSnr(),
                        dto.getRcs(),
                        dto.getElementCount(),
                        dto.getTargetLength(),
                        dto.getDetectionFrames(),
                        dto.getTrackState(),
                        dto.getReserve1(),
                        dto.getReserve2(),
                        capturedAt
                );
                entities.add(entity);
            }
            radarTargetRepository.saveAll(entities);
        } catch (Exception ex) {
            log.warn("Failed to persist radar targets", ex);
        }
    }

    @Transactional
    public void recordManualAlert(String eventId, String eventType, Integer channelId, Integer port,
                                  String level, String eventTime, String payload) {
        try {
            String normalizedId = eventId != null ? eventId : "manual-" + Instant.now().toEpochMilli();
            AlertEventEntity entity = new AlertEventEntity(normalizedId, eventType, channelId, port, level, eventTime, payload);
            alertEventRepository.save(entity);
        } catch (Exception ex) {
            log.warn("Failed to persist manual alert", ex);
        }
    }

    private String stringValue(Object value) {
        if (value == null) return null;
        String s = value.toString().trim();
        return s.isEmpty() ? null : s;
    }

    private Integer intValue(Object value) {
        if (value == null) return null;
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        try {
            String s = value.toString().trim();
            if (s.isEmpty()) return null;
            return Integer.parseInt(s);
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
