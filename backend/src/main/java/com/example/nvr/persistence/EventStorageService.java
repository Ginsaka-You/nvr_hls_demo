package com.example.nvr.persistence;

import com.example.nvr.AlertHub;
import com.example.nvr.CameraChannelBlocklist;
import com.example.nvr.ImsiHub;
import com.example.nvr.RadarController;
import com.example.nvr.events.AlertEventSavedEvent;
import com.example.nvr.imsi.ImsiRecordPayload;
import com.example.nvr.risk.CameraEvidenceService;
import com.example.nvr.risk.RiskAssessmentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;

@Service
public class EventStorageService {

    private static final Logger log = LoggerFactory.getLogger(EventStorageService.class);
    private final AlertEventRepository alertEventRepository;
    private final CameraAlarmRepository cameraAlarmRepository;
    private final RadarTargetRepository radarTargetRepository;
    private final ImsiRecordRepository imsiRecordRepository;
    private final RiskAssessmentService riskAssessmentService;
    private final ApplicationEventPublisher eventPublisher;
    private final CameraEvidenceService cameraEvidenceService;
    private final ZoneId systemZone = ZoneId.systemDefault();

    public EventStorageService(AlertEventRepository alertEventRepository,
                               CameraAlarmRepository cameraAlarmRepository,
                               RadarTargetRepository radarTargetRepository,
                               ImsiRecordRepository imsiRecordRepository,
                               RiskAssessmentService riskAssessmentService,
                               ApplicationEventPublisher eventPublisher,
                               CameraEvidenceService cameraEvidenceService) {
        this.alertEventRepository = alertEventRepository;
        this.cameraAlarmRepository = cameraAlarmRepository;
        this.radarTargetRepository = radarTargetRepository;
        this.imsiRecordRepository = imsiRecordRepository;
        this.riskAssessmentService = riskAssessmentService;
        this.eventPublisher = eventPublisher;
        this.cameraEvidenceService = cameraEvidenceService;
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
                runAfterCommit("risk assessment after IMSI sync",
                        () -> riskAssessmentService.processImsiRecordsSaved(saved));
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
            String camChannel = normalizeStreamSuffix(stringValue(event.get("camChannel")));
            if (camChannel == null) {
                camChannel = deriveCamChannel(channelId, port);
            }
            camChannel = normalizeStreamSuffix(camChannel);
            if (CameraChannelBlocklist.shouldIgnore(channelId, port, camChannel)) {
                return false;
            }
            String status = stringValue(event.get("status"));
            Instant eventInstant = parseEventInstant(eventTime);
            if (camChannel != null) {
                event.put("camChannel", camChannel);
            }
            AlertEventEntity entity = new AlertEventEntity(eventId, eventType, camChannel, level, eventTime, status);
            if (cameraEvidenceService != null && camChannel != null) {
                cameraEvidenceService.findSnapshotPath(camChannel, eventInstant)
                        .ifPresent(entity::setSnapshotPath);
            }
            AlertEventEntity saved = alertEventRepository.save(entity);
            if (!hasSnapshot(entity.getSnapshotPath()) && camChannel != null && cameraEvidenceService != null) {
                scheduleSnapshotCapture("alert-event", "自动告警抓拍", camChannel, eventInstant,
                        path -> updateAlertSnapshot(saved.getId(), path));
            }
            try {
                eventPublisher.publishEvent(new AlertEventSavedEvent(saved));
            } catch (Exception publishEx) {
                log.debug("Failed to publish alert event notification: {}", publishEx.getMessage());
            }
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
            String camChannelHint = normalizeStreamSuffix(stringValue(event.get("camChannel")));
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
            Instant eventInstant = parseEventInstant(eventTime);

            String camChannel = camChannelHint != null ? camChannelHint : deriveCamChannel(channelId, port);
            camChannel = normalizeStreamSuffix(camChannel);
            if (camChannel == null) {
                return false;
            }
            CameraAlarmEntity entity = new CameraAlarmEntity(eventId, eventType, camChannel, level, eventTime);
            if (cameraEvidenceService != null) {
                cameraEvidenceService.findSnapshotPath(camChannel, eventInstant)
                        .ifPresent(entity::setSnapshotPath);
            }
            CameraAlarmEntity saved = cameraAlarmRepository.save(entity);
            if (!hasSnapshot(entity.getSnapshotPath()) && cameraEvidenceService != null) {
                scheduleSnapshotCapture("camera-alarm", "摄像头智能告警抓拍", camChannel, eventInstant,
                        path -> updateCameraAlarmSnapshot(saved.getId(), path));
            }
            runAfterCommit("risk assessment after camera alarm",
                    () -> riskAssessmentService.processCameraAlarmSaved(saved));
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
            List<String> radarChannels = cameraEvidenceService != null
                    ? cameraEvidenceService.resolveRadarChannels(response.getHost())
                    : Collections.emptyList();
            String snapshotChannel = radarChannels.isEmpty() ? null : radarChannels.get(0);
            String snapshotPath = null;
            if (cameraEvidenceService != null && snapshotChannel != null) {
                snapshotPath = cameraEvidenceService.findSnapshotPath(snapshotChannel, capturedAt)
                        .orElse(null);
            }
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
                        capturedAt,
                        snapshotChannel,
                        snapshotPath
                );
                entities.add(entity);
            }
            List<RadarTargetEntity> saved = radarTargetRepository.saveAll(entities);
            for (RadarTargetEntity entity : saved) {
                createRadarAlertForTarget(entity);
            }
            if (cameraEvidenceService != null && !radarChannels.isEmpty()) {
                List<Long> ids = saved.stream()
                        .map(RadarTargetEntity::getId)
                        .filter(Objects::nonNull)
                        .collect(Collectors.toList());
                if (!ids.isEmpty()) {
                    for (String channel : radarChannels) {
                        String finalChannel = channel;
                        scheduleSnapshotCapture("radar-target", "雷达触发抓拍", finalChannel, capturedAt,
                                path -> {
                                    updateRadarSnapshotPaths(ids, path);
                                    updateRadarAlertSnapshots(ids, path);
                                });
                    }
                }
            }
            runAfterCommit("risk assessment after radar targets",
                    () -> riskAssessmentService.processRadarTargetsSaved(saved));
        } catch (Exception ex) {
            log.warn("Failed to persist radar targets", ex);
        }
    }

    @Transactional
    public void recordManualAlert(String eventId, String eventType, Integer channelId, Integer port,
                                  String level, String eventTime) {
        try {
            String normalizedId = eventId != null ? eventId : "manual-" + Instant.now().toEpochMilli();
            String camChannel = normalizeStreamSuffix(deriveCamChannel(channelId, port));
            if (CameraChannelBlocklist.shouldIgnore(channelId, port, camChannel)) {
                return;
            }
            Instant eventInstant = parseEventInstant(eventTime);
            AlertEventEntity entity = new AlertEventEntity(normalizedId, normalizeEventType(eventType), camChannel, level, eventTime, null);
            if (cameraEvidenceService != null && camChannel != null) {
                cameraEvidenceService.findSnapshotPath(camChannel, eventInstant)
                        .ifPresent(entity::setSnapshotPath);
            }
            AlertEventEntity saved = alertEventRepository.save(entity);
            if (!hasSnapshot(entity.getSnapshotPath()) && camChannel != null && cameraEvidenceService != null) {
                scheduleSnapshotCapture("manual-alert", "手动告警抓拍", camChannel, eventInstant,
                        path -> updateAlertSnapshot(saved.getId(), path));
            }
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

    private boolean hasSnapshot(String path) {
        return path != null && !path.isBlank();
    }

    private void scheduleSnapshotCapture(String trigger,
                                         String description,
                                         String camChannel,
                                         Instant anchorTime,
                                         Consumer<String> onSnapshotReady) {
        if (cameraEvidenceService == null || camChannel == null || camChannel.isBlank() || onSnapshotReady == null) {
            return;
        }
        Instant anchor = anchorTime != null ? anchorTime : Instant.now();
        String normalizedChannel = normalizeStreamSuffix(camChannel);
        if (normalizedChannel == null) {
            return;
        }
        runAfterCommit("snapshot-" + trigger, () -> cameraEvidenceService.captureSnapshotPath(
                        normalizedChannel,
                        anchor,
                        trigger,
                        description,
                        trigger,
                        100.0)
                .whenComplete((optionalPath, throwable) -> {
                    if (throwable != null) {
                        log.warn("Snapshot {} scheduling failed: {}", trigger, throwable.getMessage());
                        return;
                    }
                    if (optionalPath == null || optionalPath.isEmpty()) {
                        return;
                    }
                    try {
                        onSnapshotReady.accept(optionalPath.get());
                    } catch (Exception ex) {
                        log.warn("Failed to assign snapshot for {}: {}", trigger, ex.getMessage());
                    }
                }));
    }

    private void updateAlertSnapshot(Long id, String path) {
        if (id == null || !hasSnapshot(path)) {
            return;
        }
        try {
            alertEventRepository.findById(id).ifPresent(entity -> {
                if (!hasSnapshot(entity.getSnapshotPath())) {
                    entity.setSnapshotPath(path);
                    alertEventRepository.save(entity);
                }
            });
        } catch (Exception ex) {
            log.warn("Failed to update alert snapshot for {}: {}", id, ex.getMessage());
        }
    }

    private void updateCameraAlarmSnapshot(Long id, String path) {
        if (id == null || !hasSnapshot(path)) {
            return;
        }
        try {
            cameraAlarmRepository.findById(id).ifPresent(entity -> {
                if (!hasSnapshot(entity.getSnapshotPath())) {
                    entity.setSnapshotPath(path);
                    cameraAlarmRepository.save(entity);
                }
            });
        } catch (Exception ex) {
            log.warn("Failed to update camera alarm snapshot for {}: {}", id, ex.getMessage());
        }
    }

    private void updateRadarSnapshotPaths(List<Long> ids, String path) {
        if (!hasSnapshot(path) || ids == null || ids.isEmpty()) {
            return;
        }
        try {
            List<RadarTargetEntity> entities = radarTargetRepository.findAllById(ids);
            boolean updated = false;
            for (RadarTargetEntity entity : entities) {
                if (entity != null && !hasSnapshot(entity.getSnapshotPath())) {
                    entity.setSnapshotPath(path);
                    updated = true;
                }
            }
            if (updated) {
                radarTargetRepository.saveAll(entities);
            }
        } catch (Exception ex) {
            log.warn("Failed to update radar snapshot paths: {}", ex.getMessage());
        }
    }

    private void updateRadarAlertSnapshots(List<Long> radarTargetIds, String path) {
        if (!hasSnapshot(path) || radarTargetIds == null || radarTargetIds.isEmpty()) {
            return;
        }
        for (Long radarId : radarTargetIds) {
            if (radarId == null) continue;
            String eventId = "radar-" + radarId;
            try {
                alertEventRepository.findByEventId(eventId).ifPresent(alert -> {
                    if (!hasSnapshot(alert.getSnapshotPath())) {
                        alert.setSnapshotPath(path);
                        alertEventRepository.save(alert);
                    }
                });
            } catch (Exception ex) {
                log.warn("Failed to update radar alert snapshot for {}: {}", eventId, ex.getMessage());
            }
        }
    }

    private void createRadarAlertForTarget(RadarTargetEntity target) {
        if (target == null || target.getId() == null) {
            return;
        }
        String eventId = "radar-" + target.getId();
        try {
            if (alertEventRepository.existsByEventId(eventId)) {
                if (hasSnapshot(target.getSnapshotPath())) {
                    alertEventRepository.findByEventId(eventId).ifPresent(alert -> {
                        if (!hasSnapshot(alert.getSnapshotPath())) {
                            alert.setSnapshotPath(target.getSnapshotPath());
                            alertEventRepository.save(alert);
                        }
                    });
                }
                return;
            }
            String eventTime = target.getCapturedAt() != null ? target.getCapturedAt().toString() : Instant.now().toString();
            AlertEventEntity alert = new AlertEventEntity(eventId, "雷达目标", target.getCamChannel(), null, eventTime, "未处理");
            if (hasSnapshot(target.getSnapshotPath())) {
                alert.setSnapshotPath(target.getSnapshotPath());
            }
            AlertEventEntity saved = alertEventRepository.save(alert);
            try {
                eventPublisher.publishEvent(new AlertEventSavedEvent(saved));
            } catch (Exception publishEx) {
                log.debug("Failed to publish radar alert event: {}", publishEx.getMessage());
            }
        } catch (Exception ex) {
            log.warn("Failed to create radar alert {}: {}", eventId, ex.getMessage());
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

    private String deriveCamChannel(Integer channelId, Integer port) {
        if (channelId != null) {
            int physical = channelId;
            int stream = 1;
            if (channelId >= 100) {
                int prefix = channelId / 100;
                int suffix = channelId % 100;
                if (prefix > 0) {
                    physical = prefix;
                }
                if (suffix > 0) {
                    stream = suffix;
                }
            } else if (channelId > 32) {
                physical = ((channelId - 1) % 32) + 1;
                stream = ((channelId - 1) / 32) + 1;
            }
            return String.format(Locale.ROOT, "%d%02d", physical, Math.max(1, stream));
        }
        if (port != null) {
            return String.format(Locale.ROOT, "%d%02d", Math.max(1, port), 1);
        }
        return null;
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

    private Instant parseEventInstant(String eventTime) {
        if (eventTime == null || eventTime.isBlank()) {
            return Instant.now();
        }
        String trimmed = eventTime.trim();
        try {
            return Instant.parse(trimmed);
        } catch (DateTimeParseException ignored) {
        }
        DateTimeFormatter[] formatters = new DateTimeFormatter[]{
                DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
                DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss")
        };
        for (DateTimeFormatter formatter : formatters) {
            try {
                LocalDateTime ldt = LocalDateTime.parse(trimmed, formatter);
                return ldt.atZone(systemZone).toInstant();
            } catch (DateTimeParseException ignored) {
            }
        }
        try {
            long epochMillis = Long.parseLong(trimmed);
            if (String.valueOf(epochMillis).length() == 10) {
                epochMillis *= 1000;
            }
            return Instant.ofEpochMilli(epochMillis);
        } catch (NumberFormatException ignored) {
        }
        return Instant.now();
    }

    private void runAfterCommit(String description, Runnable action) {
        if (action == null) {
            return;
        }
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            safeRun(description, action);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                safeRun(description, action);
            }
        });
    }

    private void safeRun(String description, Runnable action) {
        try {
            action.run();
        } catch (Exception ex) {
            if (description == null || description.isBlank()) {
                log.warn("Deferred action failed: {}", ex.getMessage(), ex);
            } else {
                log.warn("Failed to execute {}: {}", description, ex.getMessage(), ex);
            }
        }
    }

}
