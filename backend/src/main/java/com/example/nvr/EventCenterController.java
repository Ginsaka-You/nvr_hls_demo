package com.example.nvr;

import com.example.nvr.persistence.AlertEventEntity;
import com.example.nvr.persistence.AlertEventRepository;
import com.example.nvr.persistence.CameraAlarmEntity;
import com.example.nvr.persistence.CameraAlarmRepository;
import com.example.nvr.persistence.RadarTargetEntity;
import com.example.nvr.persistence.RadarTargetRepository;
import com.example.nvr.persistence.RiskAssessmentEntity;
import com.example.nvr.persistence.RiskAssessmentRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/events")
public class EventCenterController {

    private final AlertEventRepository alertEventRepository;
    private final CameraAlarmRepository cameraAlarmRepository;
    private final RadarTargetRepository radarTargetRepository;
    private final RiskAssessmentRepository riskAssessmentRepository;
    private final ObjectMapper objectMapper;

    public EventCenterController(AlertEventRepository alertEventRepository,
                                 CameraAlarmRepository cameraAlarmRepository,
                                 RadarTargetRepository radarTargetRepository,
                                 RiskAssessmentRepository riskAssessmentRepository,
                                 ObjectMapper objectMapper) {
        this.alertEventRepository = alertEventRepository;
        this.cameraAlarmRepository = cameraAlarmRepository;
        this.radarTargetRepository = radarTargetRepository;
        this.riskAssessmentRepository = riskAssessmentRepository;
        this.objectMapper = objectMapper;
    }

    @GetMapping("/alerts")
    public List<AlertEventEntity> listAlerts(@RequestParam(name = "limit", defaultValue = "100") int limit) {
        return alertEventRepository.findAllByOrderByIdDesc(page(limit, "id")).getContent();
    }

    @GetMapping("/alerts/{id}")
    public ResponseEntity<AlertEventEntity> getAlert(@PathVariable("id") Long id) {
        return alertEventRepository.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/risk-actions")
    public List<RiskActionView> listRiskActions(@RequestParam(name = "limit", defaultValue = "100") int limit) {
        int size = clampLimit(limit);
        List<RiskAssessmentEntity> assessments = riskAssessmentRepository.findTop200ByOrderByUpdatedAtDesc();
        List<RiskActionView> actions = new ArrayList<>();
        for (RiskAssessmentEntity assessment : assessments) {
            if (actions.size() >= size) {
                break;
            }
            actions.addAll(extractRiskActions(assessment));
        }
        actions.sort((a, b) -> b.getCreatedAt().compareTo(a.getCreatedAt()));
        if (actions.size() > size) {
            return actions.subList(0, size);
        }
        return actions;
    }

    @GetMapping("/camera-alarms")
    public List<CameraAlarmEntity> listCameraAlarms(@RequestParam(name = "limit", defaultValue = "100") int limit) {
        return cameraAlarmRepository.findAllByOrderByCreatedAtDesc(page(limit, "createdAt")).getContent();
    }

    @GetMapping("/radar-targets")
    public List<RadarTargetEntity> listRadarTargets(@RequestParam(name = "limit", defaultValue = "100") int limit) {
        return radarTargetRepository.findAllByOrderByCapturedAtDesc(page(limit, "capturedAt")).getContent();
    }

    private org.springframework.data.domain.Pageable page(int limit, String sortField) {
        return org.springframework.data.domain.PageRequest.of(0, clampLimit(limit),
                org.springframework.data.domain.Sort.by(org.springframework.data.domain.Sort.Direction.DESC, sortField));
    }

    private int clampLimit(int limit) {
        return Math.max(1, Math.min(limit, 200));
    }

    private List<RiskActionView> extractRiskActions(RiskAssessmentEntity assessment) {
        Map<String, Object> details = parseDetails(assessment.getDetailsJson());
        List<?> actions = details != null && details.get("actions") instanceof List ? (List<?>) details.get("actions") : List.of();
        return actions.stream()
                .map(obj -> buildRiskView(assessment, details, obj))
                .filter(Optional::isPresent)
                .map(Optional::get)
                .collect(Collectors.toList());
    }

    private Optional<RiskActionView> buildRiskView(RiskAssessmentEntity assessment,
                                                   Map<String, Object> details,
                                                   Object rawAction) {
        if (!(rawAction instanceof Map)) {
            return Optional.empty();
        }
        Map<?, ?> action = (Map<?, ?>) rawAction;
        boolean triggered = Boolean.TRUE.equals(action.get("triggered"));
        if (!triggered) {
            return Optional.empty();
        }
        String actionId = action.containsKey("id") && action.get("id") != null
                ? action.get("id").toString().toUpperCase(Locale.ROOT)
                : null;
        if (actionId == null) {
            return Optional.empty();
        }
        Instant decidedAt = parseInstant(action.get("decidedAt"));
        String eventId = buildEventId(actionId, decidedAt);
        String resolvedEventId = eventId != null ? eventId : assessment.getId() + "-" + actionId;
        String level = classificationToLevel(assessment.getClassification());
        String status = "未处理";
        String eventType = "风控动作 " + actionId;
        String eventTime = decidedAt != null ? decidedAt.toString() : null;
        Instant createdAt = Optional.ofNullable(assessment.getUpdatedAt()).orElse(Instant.now());
        return Optional.of(new RiskActionView(
                resolvedEventId != null ? resolvedEventId : assessment.getId() + "-" + actionId,
                resolvedEventId,
                actionId,
                eventType,
                null,
                level,
                status,
                eventTime,
                createdAt,
                null,
                assessment.getClassification(),
                assessment.getSummary(),
                assessment.getScore(),
                details,
                assessment.getWindowStart(),
                assessment.getWindowEnd(),
                decidedAt != null ? decidedAt.toString() : null
        ));
    }

    private Map<String, Object> parseDetails(String json) {
        if (json == null || json.isBlank() || objectMapper == null) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception ex) {
            return Map.of();
        }
    }

    private Instant parseInstant(Object raw) {
        if (raw == null) {
            return null;
        }
        if (raw instanceof Instant) {
            return (Instant) raw;
        }
        if (raw instanceof Number) {
            return epochToInstant(((Number) raw).longValue(), String.valueOf(raw).length());
        }
        String value = raw.toString().trim();
        if (value.isEmpty()) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (Exception ex) {
            try {
                long epoch = Long.parseLong(value);
                return epochToInstant(epoch, value.length());
            } catch (Exception ignored) {
                return null;
            }
        }
    }

    private String buildEventId(String actionId, Instant decidedAt) {
        if (actionId == null || decidedAt == null) {
            return null;
        }
        return String.format(Locale.ROOT, "risk-%s-%d", actionId.toLowerCase(Locale.ROOT), decidedAt.toEpochMilli());
    }

    private Instant epochToInstant(long epoch, int digits) {
        if (epoch <= 0L) {
            return null;
        }
        return digits <= 10 ? Instant.ofEpochSecond(epoch) : Instant.ofEpochMilli(epoch);
    }

    private String classificationToLevel(String classification) {
        if (classification == null) {
            return "info";
        }
        switch (classification.toUpperCase(Locale.ROOT)) {
            case "P1":
                return "critical";
            case "P2":
                return "major";
            case "P3":
                return "minor";
            default:
                return "info";
        }
    }

    public static class RiskActionView {
        private final String id;
        private final String eventId;
        private final String action;
        private final String eventType;
        private final String camChannel;
        private final String level;
        private final String status;
        private final String eventTime;
        private final Instant createdAt;
        private final String snapshotUrl;
        private final String classification;
        private final String summary;
        private final Integer score;
        private final Map<String, Object> details;
        private final Instant windowStart;
        private final Instant windowEnd;
        private final String decidedAt;

        public RiskActionView(String id,
                              String eventId,
                              String action,
                              String eventType,
                              String camChannel,
                              String level,
                              String status,
                              String eventTime,
                              Instant createdAt,
                              String snapshotUrl,
                              String classification,
                              String summary,
                              Integer score,
                              Map<String, Object> details,
                              Instant windowStart,
                              Instant windowEnd,
                              String decidedAt) {
            this.id = id;
            this.eventId = eventId;
            this.action = action;
            this.eventType = eventType;
            this.camChannel = camChannel;
            this.level = level;
            this.status = status;
            this.eventTime = eventTime;
            this.createdAt = createdAt;
            this.snapshotUrl = snapshotUrl;
            this.classification = classification;
            this.summary = summary;
            this.score = score;
            this.details = details;
            this.windowStart = windowStart;
            this.windowEnd = windowEnd;
            this.decidedAt = decidedAt;
        }

        public String getId() {
            return id;
        }

        public String getEventId() {
            return eventId;
        }

        public String getAction() {
            return action;
        }

        public String getEventType() {
            return eventType;
        }

        public String getCamChannel() {
            return camChannel;
        }

        public String getLevel() {
            return level;
        }

        public String getStatus() {
            return status;
        }

        public String getEventTime() {
            return eventTime;
        }

        public Instant getCreatedAt() {
            return createdAt;
        }

        public String getSnapshotUrl() {
            return snapshotUrl;
        }

        public String getClassification() {
            return classification;
        }

        public String getSummary() {
            return summary;
        }

        public Integer getScore() {
            return score;
        }

        public Map<String, Object> getDetails() {
            return details;
        }

        public Instant getWindowStart() {
            return windowStart;
        }

        public Instant getWindowEnd() {
            return windowEnd;
        }

        public String getDecidedAt() {
            return decidedAt;
        }
    }
}
