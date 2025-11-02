package com.example.nvr.risk;

import com.example.nvr.persistence.CameraAlarmEntity;
import com.example.nvr.persistence.CameraAlarmRepository;
import com.example.nvr.persistence.ImsiRecordEntity;
import com.example.nvr.persistence.ImsiRecordRepository;
import com.example.nvr.persistence.RadarTargetEntity;
import com.example.nvr.persistence.RadarTargetRepository;
import com.example.nvr.persistence.RiskAssessmentEntity;
import com.example.nvr.persistence.RiskAssessmentRepository;
import com.example.nvr.risk.config.RiskModelConfig;
import com.example.nvr.risk.config.RiskModelConfig.ActionDefinition;
import com.example.nvr.risk.config.RiskModelConfig.GRuleDefinition;
import com.example.nvr.risk.config.RiskModelConfigLoader;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * 风控模型服务（P1–P4 + A1/A2/A3 重构版）。
 */
@Service
public class RiskAssessmentService {

    private static final Logger log = LoggerFactory.getLogger(RiskAssessmentService.class);

    private final RiskAssessmentRepository riskAssessmentRepository;
    private final ImsiRecordRepository imsiRecordRepository;
    private final CameraAlarmRepository cameraAlarmRepository;
    private final RadarTargetRepository radarTargetRepository;
    private final ObjectMapper objectMapper;
    private final RiskModelConfigLoader configLoader;

    public RiskAssessmentService(RiskAssessmentRepository riskAssessmentRepository,
                                 ImsiRecordRepository imsiRecordRepository,
                                 CameraAlarmRepository cameraAlarmRepository,
                                 RadarTargetRepository radarTargetRepository,
                                 ObjectMapper objectMapper,
                                 RiskModelConfigLoader configLoader) {
        this.riskAssessmentRepository = riskAssessmentRepository;
        this.imsiRecordRepository = imsiRecordRepository;
        this.cameraAlarmRepository = cameraAlarmRepository;
        this.radarTargetRepository = radarTargetRepository;
        this.objectMapper = objectMapper;
        this.configLoader = configLoader;
    }

    @Transactional
    public void processImsiRecordsSaved(List<ImsiRecordEntity> records) {
        if (records == null || records.isEmpty()) {
            return;
        }
        evaluateSiteWindow(Instant.now());
    }

    @Transactional
    public void processCameraAlarmSaved(CameraAlarmEntity alarm) {
        if (alarm == null || alarm.getCreatedAt() == null) {
            return;
        }
        evaluateSiteWindow(Instant.now());
    }

    @Transactional
    public void processRadarTargetsSaved(List<RadarTargetEntity> targets) {
        if (targets == null || targets.isEmpty()) {
            return;
        }
        evaluateSiteWindow(Instant.now());
    }

    @Transactional(readOnly = true)
    public List<RiskAssessmentEntity> findLatestAssessments(int limit) {
        int size = Math.max(1, Math.min(limit, 200));
        List<RiskAssessmentEntity> list = riskAssessmentRepository.findTop200ByOrderByUpdatedAtDesc();
        if (list.size() <= size) {
            return list;
        }
        return list.subList(0, size);
    }

    @Transactional
    public void recomputeAll() {
        evaluateSiteWindow(Instant.now());
    }

    private void evaluateSiteWindow(Instant now) {
        RiskModelConfig config = configLoader.getConfig();
        RiskModelConfig.Parameters parameters = config.getParameters();

        Instant windowStart = now.minus(parameters.getAnalysisWindow());
        Instant historyStart = now.minus(parameters.getHistoryWindow());

        List<ImsiRecordEntity> imsiHistory = imsiRecordRepository
                .findByFetchedAtBetweenOrderByFetchedAtAsc(historyStart, now);
        List<CameraAlarmEntity> cameraHistory = cameraAlarmRepository
                .findByCreatedAtBetweenOrderByCreatedAtAsc(historyStart, now);
        List<RadarTargetEntity> radarHistory = radarTargetRepository
                .findByCapturedAtBetweenOrderByCapturedAtAsc(historyStart, now);

        List<ImsiRecordEntity> imsiWindow = imsiHistory.stream()
                .filter(it -> it.getFetchedAt() != null)
                .filter(it -> !it.getFetchedAt().isBefore(windowStart) && !it.getFetchedAt().isAfter(now))
                .collect(Collectors.toList());
        List<CameraAlarmEntity> cameraWindow = cameraHistory.stream()
                .filter(it -> it.getCreatedAt() != null)
                .filter(it -> !it.getCreatedAt().isBefore(windowStart) && !it.getCreatedAt().isAfter(now))
                .collect(Collectors.toList());
        List<RadarTargetEntity> radarWindow = radarHistory.stream()
                .filter(it -> it.getCapturedAt() != null)
                .filter(it -> !it.getCapturedAt().isBefore(windowStart) && !it.getCapturedAt().isAfter(now))
                .collect(Collectors.toList());

        Map<String, FRuleEvaluation> fEvaluations = new LinkedHashMap<>();
        fEvaluations.put("F1", evaluateF1(cameraWindow, parameters));
        fEvaluations.put("F2", evaluateF2(imsiWindow, imsiHistory, windowStart, parameters));
        fEvaluations.put("F3", evaluateF3(imsiWindow, imsiHistory, windowStart, parameters));
        fEvaluations.put("F4", evaluateF4(cameraWindow, parameters));

        RiskModelConfig.PriorityDefinition priority = determinePriority(fEvaluations, config);
        List<ActionStatus> actions = determineActions(fEvaluations, priority, config, now);
        List<GRuleStatus> gStatuses = evaluateGRules(fEvaluations, priority, actions, imsiWindow, cameraWindow, now, config);
        updateActionsWithDispatch(actions, gStatuses);

        String state = deriveState(fEvaluations, actions, gStatuses);

        Map<String, Object> details = buildDetails(config, fEvaluations, actions, gStatuses, state,
                windowStart, now, imsiWindow, cameraWindow, radarWindow);
        String summary = buildSummary(priority, fEvaluations, actions, gStatuses);

        persistAssessment(now, windowStart, priority, details, summary);
    }

    private Map<String, Object> buildDetails(RiskModelConfig config,
                                             Map<String, FRuleEvaluation> fEvaluations,
                                             List<ActionStatus> actions,
                                             List<GRuleStatus> gStatuses,
                                             String state,
                                             Instant windowStart,
                                             Instant windowEnd,
                                             List<ImsiRecordEntity> imsiWindow,
                                             List<CameraAlarmEntity> cameraWindow,
                                             List<RadarTargetEntity> radarWindow) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("modelVersion", config.getVersion());
        details.put("config", config.toMetadata());
        details.put("window", Map.of(
                "start", windowStart,
                "end", windowEnd
        ));
        details.put("fRules", fEvaluations.values().stream()
                .map(eval -> eval.toMap(config.findPriority(eval.getEscalatesTo())))
                .collect(Collectors.toList()));
        details.put("actions", actions.stream()
                .map(action -> action.toMap(config.findAction(action.getId())))
                .collect(Collectors.toList()));
        details.put("gRules", gStatuses.stream()
                .map(status -> status.toMap(config.getGRules().stream()
                        .filter(def -> def.getId().equalsIgnoreCase(status.getId()))
                        .findFirst().orElse(null)))
                .collect(Collectors.toList()));
        details.put("stateMachine", Map.of(
                "current", state,
                "definition", config.getStateMachine()
        ));
        details.put("observations", Map.of(
                "imsiDevices", countDistinctImsi(imsiWindow),
                "cameraEvents", cameraWindow.size(),
                "radarTracks", radarWindow.size()
        ));
        return details;
    }

    private void persistAssessment(Instant now,
                                   Instant windowStart,
                                   RiskModelConfig.PriorityDefinition priority,
                                   Map<String, Object> details,
                                   String summary) {
        String classification = priority != null ? priority.getId() : "P4";
        RiskAssessmentEntity entity = riskAssessmentRepository
                .findTopByOrderByUpdatedAtDesc()
                .orElseGet(RiskAssessmentEntity::new);
        entity.setClassification(classification);
        entity.setScore(null);
        entity.setSummary(summary);
        entity.setWindowStart(windowStart);
        entity.setWindowEnd(now);
        entity.setUpdatedAt(now);
        entity.setDetailsJson(writeDetails(details));
        riskAssessmentRepository.save(entity);
    }

    private String writeDetails(Map<String, Object> details) {
        try {
            return objectMapper.writeValueAsString(details);
        } catch (JsonProcessingException ex) {
            log.warn("Failed to serialize risk assessment details", ex);
            return "{}";
        }
    }

    private RiskModelConfig.PriorityDefinition determinePriority(Map<String, FRuleEvaluation> fEvaluations,
                                                                  RiskModelConfig config) {
        if (fEvaluations.get("F4").isTriggered()) {
            return config.findPriority("P1");
        }
        if (fEvaluations.get("F3").isTriggered()) {
            return config.findPriority("P2");
        }
        if (fEvaluations.get("F1").isTriggered() || fEvaluations.get("F2").isTriggered()) {
            return config.findPriority("P3");
        }
        return config.findPriority("P4");
    }

    private List<ActionStatus> determineActions(Map<String, FRuleEvaluation> fEvaluations,
                                                RiskModelConfig.PriorityDefinition priority,
                                                RiskModelConfig config,
                                                Instant now) {
        List<ActionStatus> actions = new ArrayList<>();
        boolean anyF = fEvaluations.values().stream().anyMatch(FRuleEvaluation::isTriggered);
        actions.add(ActionStatus.recommended("A1", anyF,
                anyF ? "触发F规则，进入监控记录" : "未触发F规则", now));

        boolean needA2 = fEvaluations.get("F4").isTriggered() || fEvaluations.get("F3").isTriggered();
        actions.add(ActionStatus.recommended("A2", needA2,
                needA2 ? "P2及以上事件需远程挑战" : "无需远程挑战", needA2 ? now : null));

        boolean suggestA3 = priority != null && ("P1".equalsIgnoreCase(priority.getId())
                || fEvaluations.get("F3").getDuration().compareTo(config.getParameters().getChallengeWindow()) > 0);
        actions.add(ActionStatus.recommended("A3", suggestA3,
                suggestA3 ? "满足派警条件（待G规则确认）" : "未满足派警条件", null));
        return actions;
    }

    private List<GRuleStatus> evaluateGRules(Map<String, FRuleEvaluation> fEvaluations,
                                             RiskModelConfig.PriorityDefinition priority,
                                             List<ActionStatus> actions,
                                             List<ImsiRecordEntity> imsiWindow,
                                             List<CameraAlarmEntity> cameraWindow,
                                             Instant now,
                                             RiskModelConfig config) {
        List<GRuleStatus> statuses = new ArrayList<>();
        Duration challenge = config.getParameters().getChallengeWindow();

        boolean g1 = priority != null && "P1".equalsIgnoreCase(priority.getId());
        statuses.add(new GRuleStatus("G1", g1,
                g1 ? "P1事件立即派警" : "未达到P1"));

        boolean a2Executed = actions.stream().anyMatch(action -> "A2".equals(action.getId()) && action.isTriggered());
        boolean persists = persistsAcrossChallenge(imsiWindow, cameraWindow, now, challenge);
        boolean g2 = a2Executed && persists;
        statuses.add(new GRuleStatus("G2", g2,
                g2 ? "挑战窗口后目标未离开" : "未满足挑战失败条件"));

        Instant repeatStart = now.minus(config.getParameters().getRepeatWindow());
        long priorHigh = riskAssessmentRepository.findTop200ByOrderByUpdatedAtDesc().stream()
                .filter(entity -> entity.getUpdatedAt() != null)
                .filter(entity -> !entity.getUpdatedAt().isBefore(repeatStart) && entity.getUpdatedAt().isBefore(now))
                .filter(entity -> {
                    String cls = Optional.ofNullable(entity.getClassification()).orElse("");
                    return cls.equalsIgnoreCase("P1") || cls.equalsIgnoreCase("P2");
                })
                .count();
        boolean g3 = priorHigh + (priority != null && ("P1".equalsIgnoreCase(priority.getId()) || "P2".equalsIgnoreCase(priority.getId())) ? 1 : 0)
                >= config.getParameters().getRepeatThreshold();
        statuses.add(new GRuleStatus("G3", g3,
                g3 ? "重复侵扰达到阈值" : "未达到重复侵扰阈值"));
        return statuses;
    }

    private void updateActionsWithDispatch(List<ActionStatus> actions, List<GRuleStatus> gStatuses) {
        boolean dispatch = gStatuses.stream().anyMatch(GRuleStatus::isTriggered);
        actions.stream()
                .filter(action -> "A3".equals(action.getId()))
                .findFirst()
                .ifPresent(action -> action.setTriggered(dispatch));
    }

    private String deriveState(Map<String, FRuleEvaluation> fEvaluations,
                               List<ActionStatus> actions,
                               List<GRuleStatus> gStatuses) {
        boolean anyF = fEvaluations.values().stream().anyMatch(FRuleEvaluation::isTriggered);
        boolean a2 = actions.stream().anyMatch(action -> "A2".equals(action.getId()) && action.isTriggered());
        boolean a3 = actions.stream().anyMatch(action -> "A3".equals(action.getId()) && action.isTriggered());
        if (a3) {
            return "DISPATCHED";
        }
        if (a2) {
            return "CHALLENGE";
        }
        if (anyF) {
            return "MONITORING";
        }
        return "IDLE";
    }

    private String buildSummary(RiskModelConfig.PriorityDefinition priority,
                                Map<String, FRuleEvaluation> fEvaluations,
                                List<ActionStatus> actions,
                                List<GRuleStatus> gStatuses) {
        String priorityLabel = priority != null ? priority.getId() + " " + Optional.ofNullable(priority.getName()).orElse("") : "P4 低优先级";
        List<String> segments = new ArrayList<>();
        segments.add(priorityLabel.trim());

        List<String> ruleHits = fEvaluations.values().stream()
                .filter(FRuleEvaluation::isTriggered)
                .map(FRuleEvaluation::getReason)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
        if (!ruleHits.isEmpty()) {
            segments.add(String.join("；", ruleHits));
        }

        boolean dispatch = gStatuses.stream().anyMatch(GRuleStatus::isTriggered);
        if (dispatch) {
            segments.add("已触发派警");
        } else if (actions.stream().anyMatch(action -> "A2".equals(action.getId()) && action.isTriggered())) {
            segments.add("已执行远程挑战，等待反馈");
        } else if (actions.stream().anyMatch(ActionStatus::isTriggered)) {
            segments.add("保持监控记录");
        }
        return String.join(" ｜ ", segments);
    }

    private FRuleEvaluation evaluateF1(List<CameraAlarmEntity> window, RiskModelConfig.Parameters parameters) {
        List<CameraAlarmEntity> general = window.stream()
                .filter(alarm -> !isCoreAlarm(alarm))
                .sorted(Comparator.comparing(CameraAlarmEntity::getCreatedAt))
                .collect(Collectors.toList());
        if (general.isEmpty()) {
            return FRuleEvaluation.notTriggered("F1");
        }
        List<Instant> timestamps = general.stream()
                .map(CameraAlarmEntity::getCreatedAt)
                .collect(Collectors.toList());
        long occurrences = countDistinctEvents(timestamps, parameters.getCameraCooldown());
        Instant first = timestamps.get(0);
        Instant last = timestamps.get(timestamps.size() - 1);
        Map<String, Object> metrics = Map.of(
                "events", general.size(),
                "channels", collectDistinct(general.stream().map(CameraAlarmEntity::getCamChannel)
                        .filter(Objects::nonNull)
                        .collect(Collectors.toSet()))
        );
        return new FRuleEvaluation("F1", occurrences > 0, occurrences, first, last,
                "摄像头检测到一般保护区闯入", metrics, "P3");
    }

    private FRuleEvaluation evaluateF4(List<CameraAlarmEntity> window, RiskModelConfig.Parameters parameters) {
        List<CameraAlarmEntity> core = window.stream()
                .filter(this::isCoreAlarm)
                .sorted(Comparator.comparing(CameraAlarmEntity::getCreatedAt))
                .collect(Collectors.toList());
        if (core.isEmpty()) {
            return FRuleEvaluation.notTriggered("F4");
        }
        List<Instant> timestamps = core.stream()
                .map(CameraAlarmEntity::getCreatedAt)
                .collect(Collectors.toList());
        long occurrences = countDistinctEvents(timestamps, parameters.getCameraCooldown());
        Instant first = timestamps.get(0);
        Instant last = timestamps.get(timestamps.size() - 1);
        Map<String, Object> metrics = Map.of(
                "events", core.size(),
                "channels", collectDistinct(core.stream().map(CameraAlarmEntity::getCamChannel)
                        .filter(Objects::nonNull)
                        .collect(Collectors.toSet()))
        );
        return new FRuleEvaluation("F4", occurrences > 0, occurrences, first, last,
                "核心区域越界触发", metrics, "P1");
    }

    private FRuleEvaluation evaluateF2(List<ImsiRecordEntity> window,
                                       List<ImsiRecordEntity> history,
                                       Instant windowStart,
                                       RiskModelConfig.Parameters parameters) {
        Map<String, List<Instant>> groupedWindow = window.stream()
                .filter(rec -> rec.getFetchedAt() != null)
                .collect(Collectors.groupingBy(this::imsiKey,
                        Collectors.mapping(ImsiRecordEntity::getFetchedAt, Collectors.toList())));
        if (groupedWindow.isEmpty()) {
            return FRuleEvaluation.notTriggered("F2");
        }
        Set<String> seenBeforeWindow = history.stream()
                .filter(rec -> rec.getFetchedAt() != null && rec.getFetchedAt().isBefore(windowStart))
                .map(this::imsiKey)
                .collect(Collectors.toSet());
        Set<String> newDevices = groupedWindow.entrySet().stream()
                .filter(entry -> entry.getValue().stream().anyMatch(ts -> !ts.isBefore(windowStart)))
                .filter(entry -> !seenBeforeWindow.contains(entry.getKey()))
                .map(Map.Entry::getKey)
                .collect(Collectors.toCollection(() -> new TreeSet<>(String::compareTo)));
        if (newDevices.isEmpty()) {
            return FRuleEvaluation.notTriggered("F2");
        }
        Instant first = groupedWindow.get(newDevices.iterator().next()).stream()
                .min(Instant::compareTo)
                .orElse(windowStart);
        Instant last = newDevices.stream()
                .map(key -> groupedWindow.getOrDefault(key, List.of()).stream().max(Instant::compareTo).orElse(windowStart))
                .max(Instant::compareTo)
                .orElse(first);
        Map<String, Object> metrics = new HashMap<>();
        metrics.put("newDevices", newDevices);
        metrics.put("totalHits", groupedWindow.values().stream().mapToInt(List::size).sum());
        return new FRuleEvaluation("F2", true, newDevices.size(), first, last,
                String.format(Locale.CHINA, "检测到%d个未知IMSI", newDevices.size()), metrics, "P3");
    }

    private FRuleEvaluation evaluateF3(List<ImsiRecordEntity> window,
                                       List<ImsiRecordEntity> history,
                                       Instant windowStart,
                                       RiskModelConfig.Parameters parameters) {
        Duration dwellThreshold = parameters.getImsiDwellThreshold();
        Duration reentryWindow = parameters.getImsiReentryWindow();
        Map<String, List<Instant>> groupedWindow = window.stream()
                .filter(rec -> rec.getFetchedAt() != null)
                .collect(Collectors.groupingBy(this::imsiKey,
                        Collectors.mapping(ImsiRecordEntity::getFetchedAt, Collectors.toList())));
        if (groupedWindow.isEmpty()) {
            return FRuleEvaluation.notTriggered("F3");
        }
        Map<String, Instant> lastBeforeWindow = history.stream()
                .filter(rec -> rec.getFetchedAt() != null && rec.getFetchedAt().isBefore(windowStart))
                .collect(Collectors.groupingBy(this::imsiKey,
                        Collectors.collectingAndThen(Collectors.maxBy(Comparator.comparing(ImsiRecordEntity::getFetchedAt)),
                                opt -> opt.map(ImsiRecordEntity::getFetchedAt).orElse(null))));

        boolean triggered = false;
        Instant firstTrigger = null;
        Instant lastTrigger = null;
        String reason = null;
        Map<String, Object> metrics = new HashMap<>();
        List<String> offenders = new ArrayList<>();

        for (Map.Entry<String, List<Instant>> entry : groupedWindow.entrySet()) {
            List<Instant> hits = entry.getValue().stream().sorted().collect(Collectors.toList());
            if (hits.isEmpty()) {
                continue;
            }
            Instant first = hits.get(0);
            Instant last = hits.get(hits.size() - 1);
            Duration stay = Duration.between(first, last);
            boolean dwell = stay.compareTo(dwellThreshold) >= 0;
            Instant previous = lastBeforeWindow.get(entry.getKey());
            boolean reentry = previous != null && Duration.between(previous, first).compareTo(reentryWindow) <= 0;
            if (dwell || reentry || hits.size() >= 3) {
                triggered = true;
                firstTrigger = firstTrigger == null ? first : firstTrigger;
                lastTrigger = last.isAfter(Optional.ofNullable(lastTrigger).orElse(Instant.MIN)) ? last : lastTrigger;
                offenders.add(entry.getKey());
                if (reason == null) {
                    if (dwell) {
                        reason = "IMSI持续停留超过阈值";
                    } else if (reentry) {
                        reason = "IMSI在再识别窗口内重返";
                    } else {
                        reason = "IMSI在窗口内多次出现";
                    }
                }
            }
        }
        if (!triggered) {
            return FRuleEvaluation.notTriggered("F3");
        }
        metrics.put("devices", offenders);
        metrics.put("count", offenders.size());
        return new FRuleEvaluation("F3", true, offenders.size(),
                Optional.ofNullable(firstTrigger).orElse(windowStart),
                Optional.ofNullable(lastTrigger).orElse(windowStart),
                reason, metrics, "P2");
    }

    private long countDistinctEvents(List<Instant> timestamps, Duration cooldown) {
        if (timestamps.isEmpty()) {
            return 0;
        }
        Instant last = null;
        long count = 0;
        for (Instant ts : timestamps) {
            if (last == null || Duration.between(last, ts).compareTo(cooldown) > 0) {
                count++;
                last = ts;
            }
        }
        return count;
    }

    private boolean persistsAcrossChallenge(List<ImsiRecordEntity> imsiWindow,
                                            List<CameraAlarmEntity> cameraWindow,
                                            Instant now,
                                            Duration challengeWindow) {
        Instant threshold = now.minus(challengeWindow);
        boolean imsiPersist = imsiWindow.stream()
                .map(ImsiRecordEntity::getFetchedAt)
                .filter(Objects::nonNull)
                .anyMatch(ts -> !ts.isBefore(threshold));
        boolean cameraPersist = cameraWindow.stream()
                .map(CameraAlarmEntity::getCreatedAt)
                .filter(Objects::nonNull)
                .anyMatch(ts -> !ts.isBefore(threshold));
        boolean imsiHistoric = imsiWindow.stream()
                .map(ImsiRecordEntity::getFetchedAt)
                .filter(Objects::nonNull)
                .anyMatch(ts -> ts.isBefore(threshold));
        boolean cameraHistoric = cameraWindow.stream()
                .map(CameraAlarmEntity::getCreatedAt)
                .filter(Objects::nonNull)
                .anyMatch(ts -> ts.isBefore(threshold));
        return (imsiPersist && imsiHistoric) || (cameraPersist && cameraHistoric);
    }

    private boolean isCoreAlarm(CameraAlarmEntity alarm) {
        String type = normalize(alarm.getEventType());
        String level = normalize(alarm.getLevel());
        String channel = normalize(alarm.getCamChannel());
        return containsAny(type, "core", "aoi", "perimeter", "fence", "line", "boundary")
                || containsAny(level, "high", "critical", "p1", "major")
                || containsAny(channel, "core", "aoi", "inner");
    }

    private String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    private boolean containsAny(String text, String... keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private String imsiKey(ImsiRecordEntity entity) {
        if (entity.getImsi() != null && !entity.getImsi().isEmpty()) {
            return entity.getImsi();
        }
        if (entity.getDeviceId() != null && !entity.getDeviceId().isEmpty()) {
            return "DEV-" + entity.getDeviceId();
        }
        return "UNKNOWN";
    }

    private int countDistinctImsi(List<ImsiRecordEntity> window) {
        return (int) window.stream()
                .map(this::imsiKey)
                .collect(Collectors.toSet())
                .size();
    }

    private List<String> collectDistinct(Set<String> input) {
        return input.stream().sorted().collect(Collectors.toList());
    }

    private static class FRuleEvaluation {
        private final String id;
        private final boolean triggered;
        private final long occurrences;
        private final Instant firstSeen;
        private final Instant lastSeen;
        private final String reason;
        private final Map<String, Object> metrics;
        private final String escalatesTo;

        private FRuleEvaluation(String id,
                                 boolean triggered,
                                 long occurrences,
                                 Instant firstSeen,
                                 Instant lastSeen,
                                 String reason,
                                 Map<String, Object> metrics,
                                 String escalatesTo) {
            this.id = id;
            this.triggered = triggered;
            this.occurrences = occurrences;
            this.firstSeen = firstSeen;
            this.lastSeen = lastSeen;
            this.reason = reason;
            this.metrics = metrics != null ? metrics : Map.of();
            this.escalatesTo = escalatesTo;
        }

        static FRuleEvaluation notTriggered(String id) {
            return new FRuleEvaluation(id, false, 0, null, null, null, Map.of(), null);
        }

        public boolean isTriggered() {
            return triggered;
        }

        public String getReason() {
            return reason;
        }

        public String getEscalatesTo() {
            return escalatesTo;
        }

        public Duration getDuration() {
            if (firstSeen == null || lastSeen == null) {
                return Duration.ZERO;
            }
            return Duration.between(firstSeen, lastSeen);
        }

        public Map<String, Object> toMap(RiskModelConfig.PriorityDefinition priority) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", id);
            map.put("triggered", triggered);
            map.put("occurrences", occurrences);
            map.put("firstSeen", firstSeen);
            map.put("lastSeen", lastSeen);
            map.put("duration", getDuration());
            map.put("reason", reason);
            map.put("metrics", metrics);
            map.put("escalatesTo", escalatesTo);
            if (priority != null) {
                map.put("priority", priority);
            }
            return map;
        }
    }

    private static class ActionStatus {
        private final String id;
        private final boolean recommended;
        private boolean triggered;
        private final String rationale;
        private final Instant decidedAt;

        private ActionStatus(String id, boolean recommended, boolean triggered, String rationale, Instant decidedAt) {
            this.id = id;
            this.recommended = recommended;
            this.triggered = triggered;
            this.rationale = rationale;
            this.decidedAt = decidedAt;
        }

        static ActionStatus recommended(String id, boolean recommended, String rationale, Instant decidedAt) {
            return new ActionStatus(id, recommended, recommended, rationale, decidedAt);
        }

        public String getId() {
            return id;
        }

        public boolean isTriggered() {
            return triggered;
        }

        public void setTriggered(boolean triggered) {
            this.triggered = triggered;
        }

        public Map<String, Object> toMap(ActionDefinition definition) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", id);
            map.put("recommended", recommended);
            map.put("triggered", triggered);
            map.put("rationale", rationale);
            map.put("decidedAt", decidedAt);
            if (definition != null) {
                map.put("definition", definition);
            }
            return map;
        }
    }

    private static class GRuleStatus {
        private final String id;
        private final boolean triggered;
        private final String rationale;

        private GRuleStatus(String id, boolean triggered, String rationale) {
            this.id = id;
            this.triggered = triggered;
            this.rationale = rationale;
        }

        public String getId() {
            return id;
        }

        public boolean isTriggered() {
            return triggered;
        }

        public Map<String, Object> toMap(GRuleDefinition definition) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", id);
            map.put("triggered", triggered);
            map.put("rationale", rationale);
            if (definition != null) {
                map.put("definition", definition);
            }
            return map;
        }
    }
}
