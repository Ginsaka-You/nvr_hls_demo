package com.example.nvr;

import com.example.nvr.persistence.AlertEventEntity;
import com.example.nvr.persistence.AlertEventRepository;
import com.example.nvr.persistence.CameraAlarmEntity;
import com.example.nvr.persistence.CameraAlarmRepository;
import com.example.nvr.persistence.RadarTargetEntity;
import com.example.nvr.persistence.RadarTargetRepository;
import com.example.nvr.persistence.RiskAssessmentEntity;
import com.example.nvr.persistence.RiskAssessmentRepository;
import com.example.nvr.risk.CameraEvidenceService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestBody;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/events")
public class EventCenterController {

    private static final Logger log = LoggerFactory.getLogger(EventCenterController.class);
    private static final Duration A1_THROTTLE_WINDOW = Duration.ofSeconds(10);
    private static final int A1_SCORE_THRESHOLD = 15;

    private final AlertEventRepository alertEventRepository;
    private final CameraAlarmRepository cameraAlarmRepository;
    private final RadarTargetRepository radarTargetRepository;
    private final RiskAssessmentRepository riskAssessmentRepository;
    private final ObjectMapper objectMapper;
    private final CameraEvidenceService cameraEvidenceService;

    public EventCenterController(AlertEventRepository alertEventRepository,
                                 CameraAlarmRepository cameraAlarmRepository,
                                 RadarTargetRepository radarTargetRepository,
                                 RiskAssessmentRepository riskAssessmentRepository,
                                 ObjectMapper objectMapper,
                                 CameraEvidenceService cameraEvidenceService) {
        this.alertEventRepository = alertEventRepository;
        this.cameraAlarmRepository = cameraAlarmRepository;
        this.radarTargetRepository = radarTargetRepository;
        this.riskAssessmentRepository = riskAssessmentRepository;
        this.objectMapper = objectMapper;
        this.cameraEvidenceService = cameraEvidenceService;
    }

    @GetMapping("/alerts")
    public List<AlertEventEntity> listAlerts(@RequestParam(name = "limit", defaultValue = "100") int limit) {
        List<AlertEventEntity> alerts = alertEventRepository.findAllByOrderByIdDesc(page(limit, "id")).getContent();
        alerts.forEach(this::attachRiskSnapshotIfMissing);
        return alerts;
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
        List<RiskActionView> filtered = filterRiskActions(actions);
        if (filtered.size() > size) {
            return filtered.subList(0, size);
        }
        return filtered;
    }

    private List<RiskActionView> filterRiskActions(List<RiskActionView> actions) {
        if (actions == null || actions.isEmpty()) {
            return List.of();
        }
        List<RiskActionView> filtered = new ArrayList<>(actions.size());
        Map<String, Instant> lastA1ByKey = new HashMap<>();
        for (RiskActionView action : actions) {
            if (action == null) {
                continue;
            }
            if (!"A1".equalsIgnoreCase(action.getAction())) {
                filtered.add(action);
                continue;
            }
            Integer score = action.getScore();
            if (score != null && score < A1_SCORE_THRESHOLD) {
                continue;
            }
            Instant actionTime = resolveActionInstant(action);
            String key = buildA1ThrottleKey(action);
            if (key == null || key.isBlank()) {
                key = "a1:unknown";
            }
            Instant last = lastA1ByKey.get(key);
            if (last != null && actionTime != null) {
                Duration diff = Duration.between(actionTime, last);
                if (!diff.isNegative() && diff.compareTo(A1_THROTTLE_WINDOW) < 0) {
                    continue;
                }
            }
            if (actionTime != null) {
                lastA1ByKey.put(key, actionTime);
            }
            filtered.add(action);
        }
        return filtered;
    }

    private Instant resolveActionInstant(RiskActionView action) {
        if (action == null) {
            return null;
        }
        Instant decidedAt = parseInstant(action.getDecidedAt());
        if (decidedAt != null) {
            return decidedAt;
        }
        Instant eventTime = parseInstant(action.getEventTime());
        if (eventTime != null) {
            return eventTime;
        }
        return action.getCreatedAt();
    }

    private String buildA1ThrottleKey(RiskActionView action) {
        String channel = action.getCamChannel();
        if (channel == null || channel.isBlank()) {
            channel = extractChannelFromDetails(action.getDetails());
        }
        if (channel != null && !channel.isBlank()) {
            String normalized = normalizeChannelKey(channel);
            if (normalized != null && !normalized.isBlank()) {
                return "a1:cam:" + normalized;
            }
        }
        if (hasImsiSignals(action.getDetails())) {
            return "a1:imsi";
        }
        if (hasRadarSignals(action.getDetails())) {
            return "a1:radar";
        }
        return "a1:unknown";
    }

    private boolean hasImsiSignals(Map<String, Object> details) {
        Map<String, Object> signals = extractSignals(details);
        if (signals == null) {
            return false;
        }
        Object newImsi = signals.get("newImsi");
        return newImsi instanceof List && !((List<?>) newImsi).isEmpty();
    }

    private boolean hasRadarSignals(Map<String, Object> details) {
        Map<String, Object> signals = extractSignals(details);
        if (signals == null) {
            return false;
        }
        return isTrue(signals.get("radarPersist"))
                || isTrue(signals.get("radarNearCore"))
                || isTrue(signals.get("radarApproach"));
    }

    private Map<String, Object> extractSignals(Map<String, Object> details) {
        if (details == null) {
            return null;
        }
        Object signals = details.get("signals");
        if (signals instanceof Map) {
            return (Map<String, Object>) signals;
        }
        return null;
    }

    private boolean isTrue(Object value) {
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        if (value instanceof String) {
            return Boolean.parseBoolean(((String) value).trim());
        }
        return false;
    }

    private String extractChannelFromDetails(Map<String, Object> details) {
        Map<String, Object> metrics = findF3Metrics(details);
        if (metrics == null) {
            return null;
        }
        String bestRule = stringValue(metrics.get("bestRule"));
        Double bestScore = doubleValue(metrics.get("bestScore"));
        Object clustersObj = metrics.get("clusters");
        if (!(clustersObj instanceof List)) {
            return null;
        }
        Map<String, Object> bestCluster = null;
        double bestClusterScore = -1.0;
        for (Object entry : (List<?>) clustersObj) {
            if (!(entry instanceof Map)) {
                continue;
            }
            Map<?, ?> cluster = (Map<?, ?>) entry;
            String channel = stringValue(cluster.get("channel"));
            if (channel == null || channel.isBlank()) {
                continue;
            }
            String rule = stringValue(cluster.get("rule"));
            if (bestRule != null && bestRule.equalsIgnoreCase(rule)) {
                return channel;
            }
            Double score = doubleValue(cluster.get("score"));
            if (score != null && score > bestClusterScore) {
                bestClusterScore = score;
                bestCluster = (Map<String, Object>) cluster;
            } else if (bestScore != null && score != null && score.equals(bestScore)) {
                bestCluster = (Map<String, Object>) cluster;
            }
        }
        if (bestCluster != null) {
            return stringValue(bestCluster.get("channel"));
        }
        return null;
    }

    private Map<String, Object> findF3Metrics(Map<String, Object> details) {
        if (details == null) {
            return null;
        }
        Object fRulesObj = details.get("fRules");
        if (!(fRulesObj instanceof List)) {
            return null;
        }
        for (Object entry : (List<?>) fRulesObj) {
            if (!(entry instanceof Map)) {
                continue;
            }
            Map<?, ?> fRule = (Map<?, ?>) entry;
            String id = stringValue(fRule.get("id"));
            if (!"F3".equalsIgnoreCase(id)) {
                continue;
            }
            Object metrics = fRule.get("metrics");
            if (metrics instanceof Map) {
                return (Map<String, Object>) metrics;
            }
        }
        return null;
    }

    private String stringValue(Object value) {
        if (value == null) {
            return null;
        }
        String text = value.toString().trim();
        return text.isEmpty() ? null : text;
    }

    private Double doubleValue(Object value) {
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        if (value instanceof String) {
            try {
                return Double.parseDouble(((String) value).trim());
            } catch (Exception ignored) {
                return null;
            }
        }
        return null;
    }

    @GetMapping("/camera-alarms")
    public List<CameraAlarmEntity> listCameraAlarms(@RequestParam(name = "limit", defaultValue = "100") int limit) {
        return cameraAlarmRepository.findAllByOrderByCreatedAtDesc(page(limit, "createdAt")).getContent();
    }

    @GetMapping("/radar-targets")
    public List<RadarTargetEntity> listRadarTargets(@RequestParam(name = "limit", defaultValue = "100") int limit) {
        return radarTargetRepository.findAllByOrderByCapturedAtDesc(page(limit, "capturedAt")).getContent();
    }

    @PostMapping("/snapshots/view")
    public ResponseEntity<Void> logSnapshotView(@RequestBody SnapshotViewRequest request) {
        if (request == null || request.getPath() == null || request.getPath().isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        String context = request.getContext();
        if (context != null && !context.isBlank()) {
            log.info("Snapshot preview [{}]: {}", context, request.getPath());
        } else {
            log.info("Snapshot preview: {}", request.getPath());
        }
        return ResponseEntity.ok().build();
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
        SnapshotBundle snapshots = buildSnapshotBundle(assessment);
        return actions.stream()
                .map(obj -> buildRiskView(assessment, details, obj, snapshots))
                .filter(Optional::isPresent)
                .map(Optional::get)
                .collect(Collectors.toList());
    }

    private Optional<RiskActionView> buildRiskView(RiskAssessmentEntity assessment,
                                                   Map<String, Object> details,
                                                   Object rawAction,
                                                   SnapshotBundle snapshots) {
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
        AlertEventEntity alertEvent = findAlertEvent(resolvedEventId);
        String camChannel = alertEvent != null ? alertEvent.getCamChannel() : null;
        String status = alertEvent != null && alertEvent.getStatus() != null ? alertEvent.getStatus() : "未处理";
        String eventType = "风控动作 " + actionId;
        String eventTime = decidedAt != null ? decidedAt.toString()
                : (alertEvent != null ? alertEvent.getEventTime() : null);
        Instant createdAt = Optional.ofNullable(assessment.getUpdatedAt()).orElse(Instant.now());
        String snapshotUrl = alertEvent != null ? alertEvent.getSnapshotUrl() : null;
        List<String> snapshotList = mergeSnapshots(snapshotUrl, snapshots, camChannel);
        String primarySnapshot = snapshotUrl != null ? snapshotUrl : (snapshotList.isEmpty() ? null : snapshotList.get(0));
        return Optional.of(new RiskActionView(
                resolvedEventId != null ? resolvedEventId : assessment.getId() + "-" + actionId,
                resolvedEventId,
                assessment.getId(),
                actionId,
                eventType,
                camChannel,
                level,
                status,
                eventTime,
                createdAt,
                primarySnapshot,
                snapshotList,
                assessment.getClassification(),
                assessment.getSummary(),
                assessment.getScore(),
                assessment.getRemoteAlarmGateTriggered(),
                assessment.getSoundLightTriggered(),
                details,
                assessment.getWindowStart(),
                assessment.getWindowEnd(),
                decidedAt != null ? decidedAt.toString() : null
        ));
    }

    private AlertEventEntity findAlertEvent(String eventId) {
        if (eventId == null || eventId.isBlank()) {
            return null;
        }
        AlertEventEntity alertEvent = alertEventRepository.findByEventId(eventId).orElse(null);
        if (alertEvent != null) {
            attachRiskSnapshotIfMissing(alertEvent);
        }
        return alertEvent;
    }

    private void attachRiskSnapshotIfMissing(AlertEventEntity alert) {
        if (alert == null || hasSnapshot(alert.getSnapshotPath())) {
            return;
        }
        if (!isRiskAlert(alert)) {
            return;
        }
        Instant anchor = parseInstant(alert.getEventTime());
        if (anchor == null) {
            anchor = alert.getCreatedAt();
        }
        if (anchor == null) {
            anchor = Instant.now();
        }
        SnapshotCandidate candidate = findBestSnapshotCandidate(anchor, alert.getCamChannel());
        if (candidate != null && hasSnapshot(candidate.path)) {
            alert.setSnapshotPath(candidate.path);
            return;
        }
        if (cameraEvidenceService != null && alert.getCamChannel() != null) {
            cameraEvidenceService.findSnapshotPath(alert.getCamChannel(), anchor)
                    .ifPresent(alert::setSnapshotPath);
        }
    }

    private boolean isRiskAlert(AlertEventEntity alert) {
        String eventId = alert.getEventId();
        if (eventId != null && eventId.startsWith("risk-")) {
            return true;
        }
        String eventType = alert.getEventType();
        return eventType != null && eventType.contains("风控动作");
    }

    private boolean hasSnapshot(String path) {
        return path != null && !path.isBlank();
    }

    private SnapshotCandidate findBestSnapshotCandidate(Instant anchor, String camChannel) {
        if (anchor == null) {
            return null;
        }
        Duration window = Duration.ofMinutes(5);
        Instant start = anchor.minus(window);
        Instant end = anchor.plus(window);
        SnapshotCandidate best = null;

        List<CameraAlarmEntity> cameraEvents = camChannel != null
                ? cameraAlarmRepository.findByCamChannelAndCreatedAtBetweenOrderByCreatedAtAsc(camChannel, start, end)
                : cameraAlarmRepository.findByCreatedAtBetweenOrderByCreatedAtAsc(start, end);
        if (cameraEvents.isEmpty() && camChannel != null) {
            cameraEvents = cameraAlarmRepository.findByCreatedAtBetweenOrderByCreatedAtAsc(start, end);
        }
        for (CameraAlarmEntity alarm : cameraEvents) {
            if (alarm == null || !hasSnapshot(alarm.getSnapshotPath()) || alarm.getCreatedAt() == null) {
                continue;
            }
            if (camChannel != null && !channelsMatch(camChannel, alarm.getCamChannel())) {
                continue;
            }
            best = pickCloser(best, alarm.getSnapshotPath(), anchor, alarm.getCreatedAt());
        }

        List<RadarTargetEntity> radarTargets = radarTargetRepository.findByCapturedAtBetweenOrderByCapturedAtAsc(start, end);
        for (RadarTargetEntity target : radarTargets) {
            if (target == null || !hasSnapshot(target.getSnapshotPath()) || target.getCapturedAt() == null) {
                continue;
            }
            if (camChannel != null && !channelsMatch(camChannel, target.getCamChannel())) {
                continue;
            }
            best = pickCloser(best, target.getSnapshotPath(), anchor, target.getCapturedAt());
        }

        return best;
    }

    private SnapshotCandidate pickCloser(SnapshotCandidate current, String path, Instant anchor, Instant candidateTime) {
        if (path == null || anchor == null || candidateTime == null) {
            return current;
        }
        long diff = Math.abs(Duration.between(candidateTime, anchor).toMillis());
        if (current == null || diff < current.diffMillis) {
            return new SnapshotCandidate(path, diff);
        }
        return current;
    }

    private SnapshotBundle buildSnapshotBundle(RiskAssessmentEntity assessment) {
        if (assessment == null) {
            return SnapshotBundle.empty();
        }
        Instant start = assessment.getWindowStart();
        Instant end = assessment.getWindowEnd();
        if (end == null) {
            end = assessment.getUpdatedAt();
        }
        if (end == null) {
            end = Instant.now();
        }
        if (start == null) {
            start = end.minus(Duration.ofMinutes(5));
        }
        if (end.isBefore(start)) {
            Instant tmp = start;
            start = end;
            end = tmp;
        }

        SnapshotBundle bundle = new SnapshotBundle();
        List<CameraAlarmEntity> cameraEvents = cameraAlarmRepository.findByCreatedAtBetweenOrderByCreatedAtAsc(start, end);
        for (CameraAlarmEntity alarm : cameraEvents) {
            if (alarm == null) {
                continue;
            }
            String url = alarm.getSnapshotUrl();
            if (url != null) {
                bundle.add(alarm.getCamChannel(), url);
            }
        }
        List<RadarTargetEntity> radarTargets = radarTargetRepository.findByCapturedAtBetweenOrderByCapturedAtAsc(start, end);
        for (RadarTargetEntity target : radarTargets) {
            if (target == null) {
                continue;
            }
            String url = target.getSnapshotUrl();
            if (url != null) {
                bundle.add(target.getCamChannel(), url);
            }
        }
        return bundle;
    }

    private List<String> mergeSnapshots(String primary,
                                        SnapshotBundle snapshots,
                                        String camChannel) {
        LinkedHashMap<String, String> merged = new LinkedHashMap<>();
        addSnapshot(merged, primary);
        if (snapshots != null) {
            for (String url : snapshots.getByChannel(camChannel)) {
                addSnapshot(merged, url);
            }
            for (String url : snapshots.getAll()) {
                addSnapshot(merged, url);
            }
        }
        return new ArrayList<>(merged.values());
    }

    private void addSnapshot(LinkedHashMap<String, String> merged, String url) {
        if (merged == null || url == null) {
            return;
        }
        String trimmed = url.trim();
        if (trimmed.isEmpty()) {
            return;
        }
        String key = normalizeSnapshotKey(trimmed);
        if (key == null || key.isEmpty()) {
            return;
        }
        merged.putIfAbsent(key, trimmed);
    }

    private String normalizeSnapshotKey(String url) {
        if (url == null) {
            return null;
        }
        String trimmed = url.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        String path = trimmed;
        try {
            URI uri = URI.create(trimmed);
            if (uri.getPath() != null && !uri.getPath().isBlank()) {
                path = uri.getPath();
            }
        } catch (Exception ignored) {
            path = trimmed;
        }
        int cut = path.length();
        int queryIdx = path.indexOf('?');
        int hashIdx = path.indexOf('#');
        if (queryIdx >= 0) {
            cut = Math.min(cut, queryIdx);
        }
        if (hashIdx >= 0) {
            cut = Math.min(cut, hashIdx);
        }
        if (cut != path.length()) {
            path = path.substring(0, cut);
        }
        path = path.replace('\\', '/');
        boolean leadingSlash = path.startsWith("/");
        String[] parts = path.split("/");
        StringBuilder normalized = new StringBuilder();
        if (leadingSlash) {
            normalized.append('/');
        }
        for (String part : parts) {
            if (part == null || part.isBlank()) {
                continue;
            }
            String decoded = part;
            try {
                decoded = URLDecoder.decode(part, StandardCharsets.UTF_8);
            } catch (Exception ignored) {
                // keep original segment on decode failure
            }
            if (normalized.length() > 0 && normalized.charAt(normalized.length() - 1) != '/') {
                normalized.append('/');
            }
            normalized.append(decoded);
        }
        String normalizedText = normalized.toString();
        if (normalizedText.isEmpty()) {
            return null;
        }
        int markerIdx = normalizedText.indexOf("/snapshots/");
        if (markerIdx >= 0) {
            normalizedText = normalizedText.substring(markerIdx + "/snapshots/".length());
        }
        if (normalizedText.startsWith("/")) {
            normalizedText = normalizedText.substring(1);
        }
        return normalizedText.isEmpty() ? null : normalizedText;
    }

    private boolean channelsMatch(String a, String b) {
        String left = normalizeChannelKey(a);
        String right = normalizeChannelKey(b);
        if (left == null || right == null) {
            return false;
        }
        return left.equals(right);
    }

    private String normalizeChannelKey(String channel) {
        if (channel == null) {
            return null;
        }
        String trimmed = channel.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        String digits = trimmed.replaceAll("\\D+", "");
        if (!digits.isEmpty()) {
            return digits;
        }
        return trimmed.toLowerCase(Locale.ROOT);
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
        private final Long assessmentId;
        private final String action;
        private final String eventType;
        private final String camChannel;
        private final String level;
        private final String status;
        private final String eventTime;
        private final Instant createdAt;
        private final String snapshotUrl;
        private final List<String> snapshots;
        private final String classification;
        private final String summary;
        private final Integer score;
        private final Boolean remoteAlarmGateTriggered;
        private final Boolean soundLightTriggered;
        private final Map<String, Object> details;
        private final Instant windowStart;
        private final Instant windowEnd;
        private final String decidedAt;

        public RiskActionView(String id,
                              String eventId,
                              Long assessmentId,
                              String action,
                              String eventType,
                              String camChannel,
                              String level,
                              String status,
                              String eventTime,
                              Instant createdAt,
                              String snapshotUrl,
                              List<String> snapshots,
                              String classification,
                              String summary,
                              Integer score,
                              Boolean remoteAlarmGateTriggered,
                              Boolean soundLightTriggered,
                              Map<String, Object> details,
                              Instant windowStart,
                              Instant windowEnd,
                              String decidedAt) {
            this.id = id;
            this.eventId = eventId;
            this.assessmentId = assessmentId;
            this.action = action;
            this.eventType = eventType;
            this.camChannel = camChannel;
            this.level = level;
            this.status = status;
            this.eventTime = eventTime;
            this.createdAt = createdAt;
            this.snapshotUrl = snapshotUrl;
            this.snapshots = snapshots;
            this.classification = classification;
            this.summary = summary;
            this.score = score;
            this.remoteAlarmGateTriggered = remoteAlarmGateTriggered;
            this.soundLightTriggered = soundLightTriggered;
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

        public Long getAssessmentId() {
            return assessmentId;
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

        public List<String> getSnapshots() {
            return snapshots;
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

        public Boolean getRemoteAlarmGateTriggered() {
            return remoteAlarmGateTriggered;
        }

        public Boolean getSoundLightTriggered() {
            return soundLightTriggered;
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

    private static final class SnapshotBundle {
        private final List<String> all = new ArrayList<>();
        private final Map<String, List<String>> byChannel = new LinkedHashMap<>();

        static SnapshotBundle empty() {
            return new SnapshotBundle();
        }

        void add(String channel, String url) {
            if (url == null || url.isBlank()) {
                return;
            }
            if (!all.contains(url)) {
                all.add(url);
            }
            String key = normalizeChannelKeyStatic(channel);
            if (key == null) {
                return;
            }
            byChannel.computeIfAbsent(key, k -> new ArrayList<>());
            List<String> list = byChannel.get(key);
            if (!list.contains(url)) {
                list.add(url);
            }
        }

        List<String> getAll() {
            return new ArrayList<>(all);
        }

        List<String> getByChannel(String channel) {
            String key = normalizeChannelKeyStatic(channel);
            if (key == null) {
                return List.of();
            }
            List<String> list = byChannel.get(key);
            return list == null ? List.of() : new ArrayList<>(list);
        }

        private static String normalizeChannelKeyStatic(String channel) {
            if (channel == null) {
                return null;
            }
            String trimmed = channel.trim();
            if (trimmed.isEmpty()) {
                return null;
            }
            String digits = trimmed.replaceAll("\\D+", "");
            if (!digits.isEmpty()) {
                return digits;
            }
            return trimmed.toLowerCase(Locale.ROOT);
        }
    }

    private static final class SnapshotCandidate {
        private final String path;
        private final long diffMillis;

        private SnapshotCandidate(String path, long diffMillis) {
            this.path = path;
            this.diffMillis = diffMillis;
        }
    }

    public static class SnapshotViewRequest {
        private String path;
        private String context;

        public SnapshotViewRequest() {
        }

        public String getPath() {
            return path;
        }

        public void setPath(String path) {
            this.path = path;
        }

        public String getContext() {
            return context;
        }

        public void setContext(String context) {
            this.context = context;
        }
    }
}
