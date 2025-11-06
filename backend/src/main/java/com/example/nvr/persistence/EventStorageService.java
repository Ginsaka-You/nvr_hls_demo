package com.example.nvr.persistence;

import com.example.nvr.AlertHub;
import com.example.nvr.CameraChannelBlocklist;
import com.example.nvr.ImsiHub;
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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
            } catch (RuntimeException ex) {
                log.warn("Failed to persist IMSI sync summary", ex);
                throw ex;
            }
            return;
        }
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
        try {
            List<ImsiRecordEntity> saved = imsiRecordRepository.saveAll(entities);
            if (!saved.isEmpty()) {
                broadcastImsiUpdate(saved);
            }
            try {
                riskAssessmentService.processImsiRecordsSaved(saved);
            } catch (Exception ex) {
                log.warn("Failed to trigger risk assessment after IMSI sync", ex);
            }
        } catch (RuntimeException ex) {
            log.warn("Failed to persist IMSI records", ex);
            throw ex;
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
    public boolean recordAlertEvent(Map<String, Object> event, String rawPayload) {
        try {
            String eventId = stringValue(event.get("id"));
            if (eventId == null) {
                eventId = "evt-" + Instant.now().toEpochMilli();
            }
            if (eventId != null && alertEventRepository.existsByEventId(eventId)) {
                return false;
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
            if (CameraChannelBlocklist.shouldIgnore(channelId, port, camChannel)) {
                return false;
            }
            String status = stringValue(event.get("status"));

            AlertEventEntity entity = new AlertEventEntity(eventId, eventType, camChannel, level, eventTime, status);
            alertEventRepository.save(entity);
            return true;
        } catch (Exception ex) {
            log.warn("Failed to persist alert event", ex);
            return false;
        }
    }

    @Transactional
    public boolean recordCameraAlarm(Map<String, Object> event, String rawPayload) {
        try {
            Integer port = intValue(event.get("port"));
            Integer channelId = intValue(event.get("channelID"));
            String camChannelHint = stringValue(event.get("camChannel"));
            if (port == null && channelId == null && camChannelHint == null) {
                return false; // Nothing to tie the alarm back to a camera
            }
            if (CameraChannelBlocklist.shouldIgnore(channelId, port, camChannelHint)) {
                return false;
            }
            String eventId = stringValue(event.get("id"));
            if (eventId == null) {
                eventId = "cam-" + Instant.now().toEpochMilli();
            }
            if (cameraAlarmRepository.existsByEventId(eventId)) {
                return false;
            }
            String eventType = normalizeEventType(stringValue(event.get("eventType")));
            String level = stringValue(event.get("level"));
            String eventTime = stringValue(event.get("time"));

            String camChannel = camChannelHint != null ? camChannelHint : deriveCamChannel(channelId, port);
            if (camChannel == null) {
                return false;
            }
            CameraAlarmEntity entity = new CameraAlarmEntity(eventId, eventType, camChannel, level, eventTime);
            CameraAlarmEntity saved = cameraAlarmRepository.save(entity);
            riskAssessmentService.processCameraAlarmSaved(saved);
            return true;
        } catch (Exception ex) {
            log.warn("Failed to persist camera alarm", ex);
            return false;
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
            if (CameraChannelBlocklist.shouldIgnore(channelId, port, camChannel)) {
                return;
            }
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

    private void broadcastImsiUpdate(List<ImsiRecordEntity> saved) {
        try {
            Set<String> sourceFiles = new HashSet<>();
            for (ImsiRecordEntity entity : saved) {
                if (entity.getSourceFile() != null && !entity.getSourceFile().isBlank()) {
                    sourceFiles.add(entity.getSourceFile());
                }
            }
            Map<String, Object> payload = new HashMap<>();
            payload.put("type", "imsi");
            payload.put("count", saved.size());
            payload.put("timestamp", Instant.now().toString());
            if (!sourceFiles.isEmpty()) {
                payload.put("sourceFiles", sourceFiles);
            }
            ImsiHub.broadcast(payload);
        } catch (Exception ex) {
            log.debug("Failed to broadcast IMSI update over SSE", ex);
        }
    }
}
