package com.example.nvr.persistence;

import com.example.nvr.RadarController;
import com.example.nvr.imsi.ImsiRecordPayload;
import com.example.nvr.risk.RiskAssessmentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

@Service
public class EventStorageService {

    private static final Logger log = LoggerFactory.getLogger(EventStorageService.class);

    private final AlertEventRepository alertEventRepository;
    private final CameraAlarmRepository cameraAlarmRepository;
    private final RadarTargetRepository radarTargetRepository;
    private final ImsiRecordRepository imsiRecordRepository;
    private final RiskAssessmentService riskAssessmentService;

    public EventStorageService(AlertEventRepository alertEventRepository,
                               CameraAlarmRepository cameraAlarmRepository,
                               RadarTargetRepository radarTargetRepository,
                               ImsiRecordRepository imsiRecordRepository,
                               RiskAssessmentService riskAssessmentService) {
        this.alertEventRepository = alertEventRepository;
        this.cameraAlarmRepository = cameraAlarmRepository;
        this.radarTargetRepository = radarTargetRepository;
        this.imsiRecordRepository = imsiRecordRepository;
        this.riskAssessmentService = riskAssessmentService;
    }

    @Transactional
    public void recordImsiRecords(List<ImsiRecordPayload> records,
                                  Instant fetchedAt,
                                  long elapsedMs,
                                  String host,
                                  Integer port,
                                  String directory,
                                  String message) {
        if (records == null || records.isEmpty()) {
            try {
                ImsiRecordEntity summary = new ImsiRecordEntity(
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        safeTrim(host),
                        port,
                        safeTrim(directory),
                        safeTrim(message),
                        elapsedMs,
                        fetchedAt
                );
                imsiRecordRepository.save(summary);
            } catch (Exception ex) {
                log.warn("Failed to persist IMSI sync summary", ex);
            }
            return;
        }
        try {
            List<ImsiRecordEntity> entities = new ArrayList<>(records.size());
            for (ImsiRecordPayload record : records) {
                ImsiRecordEntity entity = new ImsiRecordEntity(
                        safeTrim(record.getDeviceId()),
                        safeTrim(record.getImsi()),
                        safeTrim(record.getOperator()),
                        safeTrim(record.getArea()),
                        safeTrim(record.getRptDate()),
                        safeTrim(record.getRptTime()),
                        safeTrim(record.getSourceFile()),
                        record.getLineNumber(),
                        safeTrim(host),
                        port,
                        safeTrim(directory),
                        safeTrim(message),
                        elapsedMs,
                        fetchedAt
                );
                entities.add(entity);
            }
            List<ImsiRecordEntity> saved = imsiRecordRepository.saveAll(entities);
            riskAssessmentService.processImsiRecordsSaved(saved);
        } catch (Exception ex) {
            log.warn("Failed to persist IMSI records", ex);
        }
    }

    @Transactional(readOnly = true)
    public List<ImsiRecordEntity> findRecentImsiRecords(int limit) {
        int size = Math.max(1, Math.min(limit, 2000));
        Sort sort = Sort.by(Sort.Order.desc("fetchedAt"), Sort.Order.desc("id"));
        Pageable pageable = PageRequest.of(0, size, sort);
        Page<ImsiRecordEntity> page = imsiRecordRepository.findAll(pageable);
        return page.getContent();
    }

    @Transactional
    public void recordAlertEvent(Map<String, Object> event, String rawPayload) {
        try {
            String eventId = stringValue(event.get("id"));
            if (eventId == null) {
                eventId = "evt-" + Instant.now().toEpochMilli();
            }
            String eventType = normalizeEventType(stringValue(event.get("eventType")));
            Integer channelId = intValue(event.get("channelID"));
            Integer port = intValue(event.get("port"));
            String level = stringValue(event.get("level"));
            String eventTime = stringValue(event.get("time"));
            String camChannel = stringValue(event.get("camChannel"));
            if (camChannel == null) {
                camChannel = deriveCamChannel(channelId, port);
            }
            String status = stringValue(event.get("status"));

            AlertEventEntity entity = new AlertEventEntity(eventId, eventType, camChannel, level, eventTime, status);
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
            String eventType = normalizeEventType(stringValue(event.get("eventType")));
            Integer channelId = intValue(event.get("channelID"));
            String level = stringValue(event.get("level"));
            String eventTime = stringValue(event.get("time"));

            String camChannel = deriveCamChannel(channelId, port);
            CameraAlarmEntity entity = new CameraAlarmEntity(eventId, eventType, camChannel, level, eventTime);
            CameraAlarmEntity saved = cameraAlarmRepository.save(entity);
            riskAssessmentService.processCameraAlarmSaved(saved);
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
            List<RadarTargetEntity> saved = radarTargetRepository.saveAll(entities);
            riskAssessmentService.processRadarTargetsSaved(saved);
        } catch (Exception ex) {
            log.warn("Failed to persist radar targets", ex);
        }
    }

    @Transactional
    public void recordManualAlert(String eventId, String eventType, Integer channelId, Integer port,
                                  String level, String eventTime) {
        try {
            String normalizedId = eventId != null ? eventId : "manual-" + Instant.now().toEpochMilli();
            String camChannel = deriveCamChannel(channelId, port);
            AlertEventEntity entity = new AlertEventEntity(normalizedId, normalizeEventType(eventType), camChannel, level, eventTime, null);
            alertEventRepository.save(entity);
        } catch (Exception ex) {
            log.warn("Failed to persist manual alert", ex);
        }
    }

    private String safeTrim(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
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

    private String deriveCamChannel(Integer channelId, Integer port) {
        if (channelId != null) {
            int base = channelId;
            int physical = base;
            int stream = 1;
            if (base > 32) {
                physical = ((base - 1) % 32) + 1;
                stream = ((base - 1) / 32) + 1;
            }
            return String.format("%d%02d", physical, stream);
        }
        if (port != null) {
            return String.format("%d%02d", port, 1);
        }
        return null;
    }

    private String normalizeEventType(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        String lower = trimmed.toLowerCase();
        if ("radar".equals(lower)) {
            return "检测到入侵";
        }
        if ("fielddetection".equals(lower)) {
            return "检测到区域入侵";
        }
        return trimmed;
    }
}
