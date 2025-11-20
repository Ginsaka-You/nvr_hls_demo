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
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
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
        return alertEventRepository.findAll(page(limit, Sort.by(Sort.Direction.DESC, "id"))).getContent();
    }

    @GetMapping("/alerts/{id}")
    public ResponseEntity<AlertEventEntity> getAlert(@PathVariable("id") Long id) {
        return alertEventRepository.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/risk-actions")
    public List<RiskActionView> listRiskActions(@RequestParam(name = "limit", defaultValue = "100") int limit) {
        int size = Math.max(1, Math.min(limit, 200));
        List<RiskAssessmentEntity> assessments = riskAssessmentRepository
                .findAll(PageRequest.of(0, size, Sort.by(Sort.Direction.DESC, "updatedAt")))
                .getContent();
        return assessments.stream()
                .flatMap(assessment -> extractRiskActions(assessment).stream())
                .sorted((a, b) -> b.getCreatedAt().compareTo(a.getCreatedAt()))
                .limit(size)
                .collect(Collectors.toList());
    }

    @GetMapping("/camera-alarms")
    public List<CameraAlarmEntity> listCameraAlarms(@RequestParam(name = "limit", defaultValue = "100") int limit) {
        return cameraAlarmRepository.findAll(page(limit, Sort.by(Sort.Direction.DESC, "createdAt"))).getContent();
    }

    @GetMapping("/radar-targets")
    public List<RadarTargetEntity> listRadarTargets(@RequestParam(name = "limit", defaultValue = "100") int limit) {
        return radarTargetRepository.findAll(page(limit, Sort.by(Sort.Direction.DESC, "capturedAt"))).getContent();
    }

    private PageRequest page(int limit, Sort sort) {
        int size = Math.max(1, Math.min(limit, 500));
        return PageRequest.of(0, size, sort);
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
        Instant decidedAt = parseInstant(stringValue(action.get("decidedAt")));
        String eventId = buildEventId(actionId, decidedAt);
        AlertEventEntity alert = eventId != null
                ? alertEventRepository.findByEventId(eventId).orElse(null)
                : null;
        String camChannel = alert != null ? alert.getCamChannel() : null;
        String level = classificationToLevel(assessment.getClassification());
        String status = alert != null ? alert.getStatus() : "未处理";
        String eventType = alert != null && alert.getEventType() != null
                ? alert.getEventType()
                : "风控动作 " + actionId;
        String eventTime = decidedAt != null ? decidedAt.toString() : (alert != null ? alert.getEventTime() : null);
        Instant createdAt = alert != null ? alert.getCreatedAt() : Optional.ofNullable(assessment.getUpdatedAt()).orElse(Instant.now());
        String snapshotUrl = alert != null ? alert.getSnapshotUrl() : null;
        return Optional.of(new RiskActionView(
                eventId != null ? eventId : assessment.getId() + "-" + actionId,
                eventId,
                actionId,
                eventType,
                camChannel,
                level,
                status,
                eventTime,
                createdAt,
                snapshotUrl,
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

    private Instant parseInstant(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return Instant.parse(value);
        } catch (Exception ex) {
            return null;
        }
    }

    private String stringValue(Object value) {
        return value == null ? null : value.toString();
    }

    private String buildEventId(String actionId, Instant decidedAt) {
        if (actionId == null || decidedAt == null) {
            return null;
        }
        return String.format(Locale.ROOT, "risk-%s-%d", actionId.toLowerCase(Locale.ROOT), decidedAt.toEpochMilli());
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
