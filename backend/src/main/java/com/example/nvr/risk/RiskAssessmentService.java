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
import com.example.nvr.risk.config.RiskModelConfig.Parameters;
import com.example.nvr.risk.config.RiskModelConfig.PriorityDefinition;
import com.example.nvr.risk.config.RiskModelConfigLoader;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 风控模型服务（综合得分 + G 闸门 + A 动作）。
 */
@Service
public class RiskAssessmentService {

    private static final Logger log = LoggerFactory.getLogger(RiskAssessmentService.class);
    private static final double MULTI_SOURCE_MULTIPLIER = 1.2;
    private static final double NIGHT_MULTIPLIER = 1.5;
    private static final double APPROACH_THRESHOLD_METERS = 1.5;

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

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void processImsiRecordsSaved(List<ImsiRecordEntity> records) {
        if (records == null || records.isEmpty()) {
            return;
        }
        evaluateSiteWindow(Instant.now());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void processCameraAlarmSaved(CameraAlarmEntity alarm) {
        if (alarm == null || alarm.getCreatedAt() == null) {
            return;
        }
        evaluateSiteWindow(Instant.now());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
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
        Parameters params = config.getParameters();
        ZoneId zone = resolveZone(params);

        Instant windowStart = now.minus(params.getMergeWindow());
        Duration historyDuration = params.getHistoryWindow().compareTo(params.getImsiReentryWindow()) >= 0
                ? params.getHistoryWindow()
                : params.getImsiReentryWindow();
        Instant historyStart = now.minus(historyDuration);
        Instant challengeStart = now.minus(params.getChallengeWindow());

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

        EvaluationContext context = new EvaluationContext();
        FRuleEvaluation f1 = evaluateF1(imsiWindow, imsiHistory, windowStart, params, context);
        FRuleEvaluation f2 = evaluateF2(radarWindow, radarHistory, challengeStart, params, context);
        FRuleEvaluation f3 = evaluateF3(cameraWindow, cameraHistory, challengeStart, context);
        context.computeLinkF1F2(params);

        Map<String, FRuleEvaluation> fEvaluations = new LinkedHashMap<>();
        fEvaluations.put("F1", f1);
        fEvaluations.put("F2", f2);
        fEvaluations.put("F3", f3);

        double baseScore = fEvaluations.values().stream().mapToDouble(FRuleEvaluation::getScore).sum();
        boolean multiSource = fEvaluations.values().stream().filter(FRuleEvaluation::isTriggered).count() >= 2;
        double afterMulti = multiSource ? baseScore * MULTI_SOURCE_MULTIPLIER : baseScore;
        boolean night = isNight(now, zone, params);
        double timeMultiplier = night ? NIGHT_MULTIPLIER : 1.0;
        double finalScore = afterMulti * timeMultiplier;
        ScoreSummary scoreSummary = new ScoreSummary(baseScore, multiSource, afterMulti, timeMultiplier, finalScore);

        PriorityDefinition priority = determinePriority(finalScore, config);
        List<GRuleStatus> gStatuses = evaluateGRules(priority, context, scoreSummary, night, config);
        List<ActionStatus> actions = determineActions(scoreSummary, gStatuses, night, now);

        String summary = buildSummary(priority, scoreSummary, context, gStatuses, actions);
        Map<String, Object> details = buildDetails(config, scoreSummary, context, fEvaluations, actions, gStatuses,
                windowStart, now, imsiWindow, cameraWindow, radarWindow);
        persistAssessment(now, windowStart, priority, scoreSummary, details, summary);
    }

    private ZoneId resolveZone(Parameters params) {
        if (params == null || params.getTimezone() == null) {
            return ZoneId.systemDefault();
        }
        try {
            return ZoneId.of(params.getTimezone());
        } catch (Exception ex) {
            log.warn("Invalid timezone '{}' in risk model config, falling back to system default", params.getTimezone());
            return ZoneId.systemDefault();
        }
    }

    private boolean isNight(Instant now, ZoneId zone, Parameters params) {
        ZonedDateTime zoned = ZonedDateTime.ofInstant(now, zone);
        int hour = zoned.getHour();
        int nightStart = params.getNightStartHour();
        int nightEnd = params.getNightEndHour();
        if (nightStart == nightEnd) {
            return false;
        }
        if (nightStart > nightEnd) {
            return hour >= nightStart || hour < nightEnd;
        }
        return hour >= nightStart && hour < nightEnd;
    }

    private PriorityDefinition determinePriority(double score, RiskModelConfig config) {
        if (score >= 70.0) {
            return config.findPriority("P1");
        }
        if (score >= 40.0) {
            return config.findPriority("P2");
        }
        if (score >= 15.0) {
            return config.findPriority("P3");
        }
        return config.findPriority("P4");
    }

    private FRuleEvaluation evaluateF1(List<ImsiRecordEntity> window,
                                        List<ImsiRecordEntity> history,
                                        Instant windowStart,
                                        Parameters params,
                                        EvaluationContext context) {
        Duration dedup = params.getImsiDedupWindow();
        Duration reentry = params.getImsiReentryWindow();
        Set<String> whitelist = params.getImsiWhitelist().stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(str -> !str.isEmpty())
                .collect(Collectors.toCollection(LinkedHashSet::new));

        Map<String, Instant> lastBeforeWindow = new HashMap<>();
        for (ImsiRecordEntity record : history) {
            Instant ts = record.getFetchedAt();
            String imsi = normalizeImsi(record.getImsi());
            if (ts == null || imsi == null || whitelist.contains(imsi)) {
                continue;
            }
            if (ts.isBefore(windowStart)) {
                lastBeforeWindow.merge(imsi, ts, (existing, candidate) -> existing.isAfter(candidate) ? existing : candidate);
            }
        }

        Map<String, Instant> firstInWindow = new HashMap<>();
        for (ImsiRecordEntity record : window) {
            Instant ts = record.getFetchedAt();
            String imsi = normalizeImsi(record.getImsi());
            if (ts == null || imsi == null || whitelist.contains(imsi)) {
                continue;
            }
            firstInWindow.merge(imsi, ts, (existing, candidate) -> existing.isBefore(candidate) ? existing : candidate);
        }

        if (firstInWindow.isEmpty()) {
            return FRuleEvaluation.notTriggered("F1");
        }

        context.registerImsi(firstInWindow);

        int newCount = 0;
        int repeatCount = 0;
        List<String> newDevices = new ArrayList<>();
        List<String> repeatDevices = new ArrayList<>();
        for (Map.Entry<String, Instant> entry : firstInWindow.entrySet()) {
            String imsi = entry.getKey();
            Instant first = entry.getValue();
            Instant previous = lastBeforeWindow.get(imsi);
            boolean isNew = previous == null || Duration.between(previous, first).compareTo(dedup) > 0;
            boolean isRepeat = previous != null && Duration.between(previous, first).compareTo(reentry) <= 0;
            if (isNew) {
                newCount++;
                newDevices.add(imsi);
            }
            if (isRepeat) {
                repeatCount++;
                repeatDevices.add(imsi);
            }
        }

        double score = Math.min(newCount, 2) * 10.0 + repeatCount * 8.0;
        List<ScoreContribution> contributions = new ArrayList<>();
        if (newCount > 0) {
            contributions.add(new ScoreContribution("outer_unknown_cnt",
                    Math.min(newCount, 2) * 10.0,
                    String.format(Locale.CHINA, "非白名单 IMSI 首现 %d 个", newCount)));
        }
        if (repeatCount > 0) {
            contributions.add(new ScoreContribution("outer_repeat",
                    repeatCount * 8.0,
                    String.format(Locale.CHINA, "30 分钟内重复出现 %d 个", repeatCount)));
        }

        context.markImsiRepeats(repeatDevices);

        Instant first = firstInWindow.values().stream().min(Comparator.naturalOrder()).orElse(windowStart);
        Instant last = firstInWindow.values().stream().max(Comparator.naturalOrder()).orElse(first);
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("newDevices", newDevices);
        metrics.put("repeatDevices", repeatDevices);
        metrics.put("count", firstInWindow.size());

        String reason = String.format(Locale.CHINA, "未知 IMSI %d 个，重复 %d 个", newCount, repeatCount);
        return new FRuleEvaluation("F1", score > 0, newCount + repeatCount, first, last, reason, metrics, contributions, null, score);
    }

    private FRuleEvaluation evaluateF2(List<RadarTargetEntity> window,
                                        List<RadarTargetEntity> history,
                                        Instant challengeStart,
                                        Parameters params,
                                        EvaluationContext context) {
        if (window.isEmpty()) {
            registerRadarHistory(history, challengeStart, context);
            return FRuleEvaluation.notTriggered("F2");
        }

        Map<String, List<RadarTargetEntity>> grouped = window.stream()
                .filter(target -> target.getCapturedAt() != null)
                .collect(Collectors.groupingBy(this::radarTrackKey));
        if (grouped.isEmpty()) {
            registerRadarHistory(history, challengeStart, context);
            return FRuleEvaluation.notTriggered("F2");
        }

        int shortCount = 0;
        int persistCount = 0;
        int approachCount = 0;
        int nearCoreCount = 0;
        Instant first = null;
        Instant last = null;

        for (List<RadarTargetEntity> track : grouped.values()) {
            List<RadarTargetEntity> sorted = track.stream()
                    .filter(t -> t.getCapturedAt() != null)
                    .sorted(Comparator.comparing(RadarTargetEntity::getCapturedAt))
                    .collect(Collectors.toList());
            if (sorted.isEmpty()) {
                continue;
            }
            Instant trackFirst = sorted.get(0).getCapturedAt();
            Instant trackLast = sorted.get(sorted.size() - 1).getCapturedAt();
            first = first == null || trackFirst.isBefore(first) ? trackFirst : first;
            last = last == null || trackLast.isAfter(last) ? trackLast : last;
            context.registerRadarTrack(trackFirst, trackLast);

            Duration duration = Duration.between(trackFirst, trackLast);
            boolean persist = duration.compareTo(params.getRadarPersistThreshold()) >= 0;
            boolean nearCore = sorted.stream()
                    .mapToDouble(this::distanceMeters)
                    .filter(d -> !Double.isNaN(d) && !Double.isInfinite(d))
                    .anyMatch(d -> d <= params.getRadarNearCoreMeters());
            double firstDistance = distanceMeters(sorted.get(0));
            double lastDistance = distanceMeters(sorted.get(sorted.size() - 1));
            boolean approach = !Double.isNaN(firstDistance) && !Double.isNaN(lastDistance)
                    && firstDistance - lastDistance >= APPROACH_THRESHOLD_METERS;

            if (persist) {
                persistCount++;
                context.setRadarPersist(true);
            } else {
                shortCount++;
            }
            if (nearCore) {
                nearCoreCount++;
                context.setRadarNearCore(true);
            }
            if (approach) {
                approachCount++;
                context.setRadarApproach(true);
            }
        }

        registerRadarHistory(history, challengeStart, context);

        double score = shortCount * 10.0 + persistCount * 20.0 + approachCount * 8.0 + nearCoreCount * 20.0;
        if (score <= 0) {
            return FRuleEvaluation.notTriggered("F2");
        }

        List<ScoreContribution> contributions = new ArrayList<>();
        if (shortCount > 0) {
            contributions.add(new ScoreContribution("mid_short",
                    shortCount * 10.0,
                    String.format(Locale.CHINA, "短暂人形轨迹 %d 条", shortCount)));
        }
        if (persistCount > 0) {
            contributions.add(new ScoreContribution("mid_persist",
                    persistCount * 20.0,
                    String.format(Locale.CHINA, "持续 ≥10s 轨迹 %d 条", persistCount)));
        }
        if (approachCount > 0) {
            contributions.add(new ScoreContribution("approach_core",
                    approachCount * 8.0,
                    String.format(Locale.CHINA, "逼近核心轨迹 %d 条", approachCount)));
        }
        if (nearCoreCount > 0) {
            contributions.add(new ScoreContribution("near_core_10m",
                    nearCoreCount * 20.0,
                    String.format(Locale.CHINA, "进入 ≤10m 轨迹 %d 条", nearCoreCount)));
        }

        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("tracks", grouped.size());
        metrics.put("persist", persistCount);
        metrics.put("short", shortCount);
        metrics.put("approach", approachCount);
        metrics.put("nearCore", nearCoreCount);

        String reason = String.format(Locale.CHINA,
                "雷达轨迹：持续 %d，短暂 %d，逼近 %d，近域 %d",
                persistCount, shortCount, approachCount, nearCoreCount);
        return new FRuleEvaluation("F2", true, grouped.size(), first, last, reason, metrics, contributions, null, score);
    }

    private void registerRadarHistory(List<RadarTargetEntity> history,
                                      Instant challengeStart,
                                      EvaluationContext context) {
        for (RadarTargetEntity target : history) {
            Instant ts = target.getCapturedAt();
            if (ts == null) {
                continue;
            }
            double distance = distanceMeters(target);
            if (Double.isNaN(distance) || Double.isInfinite(distance) || distance > 200.0) {
                continue;
            }
            if (ts.isBefore(challengeStart)) {
                context.setRadarBeforeChallenge(true);
            } else {
                context.setRadarAfterChallenge(true);
            }
        }
    }

    private FRuleEvaluation evaluateF3(List<CameraAlarmEntity> window,
                                        List<CameraAlarmEntity> history,
                                        Instant challengeStart,
                                        EvaluationContext context) {
        List<CameraAlarmEntity> coreWindow = window.stream()
                .filter(this::isCoreCameraEvent)
                .collect(Collectors.toList());
        List<CameraAlarmEntity> coreHistory = history.stream()
                .filter(this::isCoreCameraEvent)
                .collect(Collectors.toList());

        if (coreHistory.stream().anyMatch(event -> {
            Instant ts = event.getCreatedAt();
            return ts != null && ts.isBefore(challengeStart);
        })) {
            context.setCoreBeforeChallenge(true);
        }
        if (coreHistory.stream().anyMatch(event -> {
            Instant ts = event.getCreatedAt();
            return ts != null && !ts.isBefore(challengeStart);
        })) {
            context.setCoreAfterChallenge(true);
        }

        if (coreWindow.isEmpty()) {
            return FRuleEvaluation.notTriggered("F3");
        }

        context.setCoreHuman(true);
        int occurrences = coreWindow.size();
        Instant first = coreWindow.stream()
                .map(CameraAlarmEntity::getCreatedAt)
                .filter(Objects::nonNull)
                .min(Comparator.naturalOrder())
                .orElse(challengeStart);
        Instant last = coreWindow.stream()
                .map(CameraAlarmEntity::getCreatedAt)
                .filter(Objects::nonNull)
                .max(Comparator.naturalOrder())
                .orElse(first);

        double score = 60.0 * occurrences;
        List<ScoreContribution> contributions = List.of(new ScoreContribution("core_human", score,
                occurrences > 1
                        ? String.format(Locale.CHINA, "核心摄像头见人 %d 次", occurrences)
                        : "核心摄像头见人"));

        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("events", occurrences);
        metrics.put("channels", coreWindow.stream()
                .map(CameraAlarmEntity::getCamChannel)
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.toList()));

        return new FRuleEvaluation("F3", true, occurrences, first, last,
                "核心摄像头识别人形", metrics, contributions, null, score);
    }

    private List<GRuleStatus> evaluateGRules(PriorityDefinition priority,
                                             EvaluationContext context,
                                             ScoreSummary scores,
                                             boolean night,
                                             RiskModelConfig config) {
        List<GRuleStatus> statuses = new ArrayList<>();
        boolean highPriority = priority != null
                && ("P1".equalsIgnoreCase(priority.getId()) || "P2".equalsIgnoreCase(priority.getId()));
        boolean g1 = night && (context.hasCoreHuman
                || (highPriority && (context.hasRadarPersist || context.hasRadarNearCore || context.linkF1F2)));
        String g1Reason;
        if (!night) {
            g1Reason = "仅夜间触发";
        } else if (context.hasCoreHuman) {
            g1Reason = "核心摄像头见人，进入挑战";
        } else if (highPriority && (context.hasRadarPersist || context.hasRadarNearCore || context.linkF1F2)) {
            g1Reason = "P≥P2 且雷达/IMSI 形成链路";
        } else {
            g1Reason = "未满足触发条件";
        }
        statuses.add(new GRuleStatus("G1", g1, g1Reason));

        boolean challengePersists = (context.coreBeforeChallenge && context.coreAfterChallenge)
                || ((context.radarBeforeChallenge || context.hasRadarPersist) && context.radarAfterChallenge);
        boolean g2 = night && g1 && challengePersists;
        String g2Reason;
        if (!night) {
            g2Reason = "仅夜间允许 A3";
        } else if (!g1) {
            g2Reason = "尚未执行 A2";
        } else if (challengePersists) {
            g2Reason = "挑战窗口到期仍有异常";
        } else {
            g2Reason = "挑战窗口内已消失";
        }
        statuses.add(new GRuleStatus("G2", g2, g2Reason));

        boolean g3 = !night && scores.getBaseScore() > 0.0;
        statuses.add(new GRuleStatus("G3", g3, g3 ? "白天仅取证" : "夜间或未触发"));
        return statuses;
    }

    private List<ActionStatus> determineActions(ScoreSummary scores,
                                                List<GRuleStatus> gStatuses,
                                                boolean night,
                                                Instant now) {
        List<ActionStatus> actions = new ArrayList<>();
        boolean anyScore = scores.getBaseScore() > 0.0;
        actions.add(ActionStatus.create("A1", anyScore, anyScore,
                anyScore ? "F 规则得分，执行取证" : "未触发 F 规则", now));

        boolean g1 = gStatuses.stream().anyMatch(rule -> "G1".equals(rule.getId()) && rule.isTriggered());
        actions.add(ActionStatus.create("A2", g1, g1,
                g1 ? "夜间挑战已执行" : (night ? "未命中 G1" : "白天不执行 A2"),
                g1 ? now : null));

        boolean g2 = gStatuses.stream().anyMatch(rule -> "G2".equals(rule.getId()) && rule.isTriggered());
        actions.add(ActionStatus.create("A3", g2, g2,
                g2 ? "挑战失败，已通知安保" : (night ? "未触发 G2" : "白天禁用 A3"),
                g2 ? now : null));
        return actions;
    }

    private String buildSummary(PriorityDefinition priority,
                                 ScoreSummary scores,
                                 EvaluationContext context,
                                 List<GRuleStatus> gStatuses,
                                 List<ActionStatus> actions) {
        String priorityLabel = Optional.ofNullable(priority)
                .map(def -> def.getId() + " " + Optional.ofNullable(def.getName()).orElse(""))
                .orElse("P4 低优先级");
        List<String> segments = new ArrayList<>();
        segments.add(String.format(Locale.CHINA, "综合得分 %.1f → %s", scores.getTotalScore(), priorityLabel.trim()));
        if (!context.newImsi.isEmpty()) {
            segments.add(String.format(Locale.CHINA, "未知 IMSI %d", context.newImsi.size()));
        }
        if (context.hasRadarNearCore) {
            segments.add("雷达近域接近");
        }
        if (context.hasRadarApproach) {
            segments.add("雷达向核心逼近");
        }
        if (context.hasCoreHuman) {
            segments.add("核心摄像头见人");
        }
        if (gStatuses.stream().anyMatch(rule -> "G2".equals(rule.getId()) && rule.isTriggered())) {
            segments.add("已派安保出动");
        } else if (gStatuses.stream().anyMatch(rule -> "G1".equals(rule.getId()) && rule.isTriggered())) {
            segments.add("已执行远程挑战");
        } else if (actions.stream().anyMatch(action -> "A1".equals(action.getId()) && action.isTriggered())) {
            segments.add("已记录取证");
        }
        return String.join(" ｜ ", segments);
    }

    private Map<String, Object> buildDetails(RiskModelConfig config,
                                             ScoreSummary scores,
                                             EvaluationContext context,
                                             Map<String, FRuleEvaluation> fEvaluations,
                                             List<ActionStatus> actions,
                                             List<GRuleStatus> gStatuses,
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
        details.put("scores", scores.toMap());
        details.put("signals", context.toMap());
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
        details.put("observations", Map.of(
                "imsiDevices", imsiWindow.size(),
                "cameraEvents", cameraWindow.size(),
                "radarTracks", radarWindow.size()
        ));
        return details;
    }

    private void persistAssessment(Instant now,
                                   Instant windowStart,
                                   PriorityDefinition priority,
                                   ScoreSummary scores,
                                   Map<String, Object> details,
                                   String summary) {
        String classification = priority != null ? priority.getId() : "P4";
        RiskAssessmentEntity entity = riskAssessmentRepository
                .findTopByOrderByUpdatedAtDesc()
                .orElseGet(RiskAssessmentEntity::new);
        entity.setClassification(classification);
        entity.setScore((int) Math.round(scores.getTotalScore()));
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

    private String normalizeImsi(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String radarTrackKey(RadarTargetEntity target) {
        String host = Optional.ofNullable(target.getRadarHost()).orElse("radar");
        String id = target.getTargetId() != null ? String.valueOf(target.getTargetId()) : String.valueOf(System.identityHashCode(target));
        return host + "#" + id;
    }

    private double distanceMeters(RadarTargetEntity target) {
        if (target.getRange() != null) {
            return target.getRange();
        }
        Double longitudinal = target.getLongitudinalDistance();
        Double lateral = target.getLateralDistance();
        if (longitudinal == null || lateral == null) {
            return Double.NaN;
        }
        return Math.hypot(longitudinal, lateral);
    }

    private boolean isCoreCameraEvent(CameraAlarmEntity alarm) {
        String type = Optional.ofNullable(alarm.getEventType()).orElse("").toLowerCase(Locale.ROOT);
        String level = Optional.ofNullable(alarm.getLevel()).orElse("").toLowerCase(Locale.ROOT);
        return type.contains("core") || type.contains("核心") || type.contains("cross")
                || type.contains("越界") || level.contains("core") || level.contains("一级");
    }

    private static class EvaluationContext {
        private final Map<String, Instant> imsiFirstSeen = new HashMap<>();
        private final List<String> newImsi = new ArrayList<>();
        private final List<String> repeatedImsi = new ArrayList<>();
        private final List<Instant> radarFirstDetections = new ArrayList<>();
        private final List<Instant> radarLastDetections = new ArrayList<>();
        private boolean hasRadarPersist;
        private boolean hasRadarNearCore;
        private boolean hasRadarApproach;
        private boolean hasCoreHuman;
        private boolean radarBeforeChallenge;
        private boolean radarAfterChallenge;
        private boolean coreBeforeChallenge;
        private boolean coreAfterChallenge;
        private boolean linkF1F2;

        void registerImsi(Map<String, Instant> first) {
            imsiFirstSeen.putAll(first);
            newImsi.addAll(first.keySet());
        }

        void markImsiRepeats(Collection<String> repeat) {
            if (!repeat.isEmpty()) {
                repeatedImsi.addAll(repeat);
            }
        }

        void registerRadarTrack(Instant first, Instant last) {
            radarFirstDetections.add(first);
            radarLastDetections.add(last);
        }

        void computeLinkF1F2(Parameters params) {
            if (imsiFirstSeen.isEmpty() || radarFirstDetections.isEmpty()) {
                linkF1F2 = false;
                return;
            }
            Duration linkWindow = params.getF1ToF2LinkWindow();
            for (Instant imsiTime : imsiFirstSeen.values()) {
                boolean matched = radarFirstDetections.stream()
                        .anyMatch(radarTime -> !radarTime.isBefore(imsiTime)
                                && Duration.between(imsiTime, radarTime).compareTo(linkWindow) <= 0);
                if (matched) {
                    linkF1F2 = true;
                    return;
                }
            }
            linkF1F2 = false;
        }

        void setRadarPersist(boolean value) {
            hasRadarPersist = hasRadarPersist || value;
        }

        void setRadarNearCore(boolean value) {
            hasRadarNearCore = hasRadarNearCore || value;
        }

        void setRadarApproach(boolean value) {
            hasRadarApproach = hasRadarApproach || value;
        }

        void setCoreHuman(boolean value) {
            hasCoreHuman = hasCoreHuman || value;
        }

        void setRadarBeforeChallenge(boolean value) {
            radarBeforeChallenge = radarBeforeChallenge || value;
        }

        void setRadarAfterChallenge(boolean value) {
            radarAfterChallenge = radarAfterChallenge || value;
        }

        void setCoreBeforeChallenge(boolean value) {
            coreBeforeChallenge = coreBeforeChallenge || value;
        }

        void setCoreAfterChallenge(boolean value) {
            coreAfterChallenge = coreAfterChallenge || value;
        }

        Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("newImsi", new ArrayList<>(newImsi));
            map.put("repeatImsi", new ArrayList<>(repeatedImsi));
            map.put("linkF1F2", linkF1F2);
            map.put("radarPersist", hasRadarPersist);
            map.put("radarNearCore", hasRadarNearCore);
            map.put("radarApproach", hasRadarApproach);
            map.put("coreHuman", hasCoreHuman);
            map.put("radarBeforeChallenge", radarBeforeChallenge);
            map.put("radarAfterChallenge", radarAfterChallenge);
            map.put("coreBeforeChallenge", coreBeforeChallenge);
            map.put("coreAfterChallenge", coreAfterChallenge);
            return map;
        }
    }

    private static class ScoreSummary {
        private final double baseScore;
        private final boolean multiSourceApplied;
        private final double afterMultiSource;
        private final double timeMultiplier;
        private final double totalScore;

        ScoreSummary(double baseScore, boolean multiSourceApplied, double afterMultiSource, double timeMultiplier, double totalScore) {
            this.baseScore = baseScore;
            this.multiSourceApplied = multiSourceApplied;
            this.afterMultiSource = afterMultiSource;
            this.timeMultiplier = timeMultiplier;
            this.totalScore = totalScore;
        }

        double getBaseScore() {
            return baseScore;
        }

        double getTotalScore() {
            return totalScore;
        }

        Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("base", baseScore);
            map.put("multiSourceApplied", multiSourceApplied);
            map.put("afterMultiSource", afterMultiSource);
            map.put("timeMultiplier", timeMultiplier);
            map.put("total", totalScore);
            return map;
        }
    }

    private static class FRuleEvaluation {
        private final String id;
        private final boolean triggered;
        private final int occurrences;
        private final Instant firstSeen;
        private final Instant lastSeen;
        private final String reason;
        private final Map<String, Object> metrics;
        private final List<ScoreContribution> contributions;
        private final String escalatesTo;
        private final double score;

        FRuleEvaluation(String id,
                        boolean triggered,
                        int occurrences,
                        Instant firstSeen,
                        Instant lastSeen,
                        String reason,
                        Map<String, Object> metrics,
                        List<ScoreContribution> contributions,
                        String escalatesTo,
                        double score) {
            this.id = id;
            this.triggered = triggered;
            this.occurrences = occurrences;
            this.firstSeen = firstSeen;
            this.lastSeen = lastSeen;
            this.reason = reason;
            this.metrics = metrics != null ? metrics : Map.of();
            this.contributions = contributions != null ? contributions : List.of();
            this.escalatesTo = escalatesTo;
            this.score = score;
        }

        static FRuleEvaluation notTriggered(String id) {
            return new FRuleEvaluation(id, false, 0, null, null, null, Map.of(), List.of(), null, 0.0);
        }

        boolean isTriggered() {
            return triggered;
        }

        double getScore() {
            return score;
        }

        String getEscalatesTo() {
            return escalatesTo;
        }

        Map<String, Object> toMap(PriorityDefinition priority) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", id);
            map.put("triggered", triggered);
            map.put("occurrences", occurrences);
            map.put("firstSeen", firstSeen);
            map.put("lastSeen", lastSeen);
            map.put("duration", (firstSeen != null && lastSeen != null) ? Duration.between(firstSeen, lastSeen) : Duration.ZERO);
            map.put("reason", reason);
            map.put("metrics", metrics);
            map.put("score", score);
            map.put("contributions", contributions.stream().map(ScoreContribution::toMap).collect(Collectors.toList()));
            if (priority != null) {
                map.put("priority", priority);
            }
            return map;
        }
    }

    private static class ScoreContribution {
        private final String id;
        private final double value;
        private final String description;

        ScoreContribution(String id, double value, String description) {
            this.id = id;
            this.value = value;
            this.description = description;
        }

        Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", id);
            map.put("value", value);
            map.put("description", description);
            return map;
        }
    }

    private static class ActionStatus {
        private final String id;
        private final boolean recommended;
        private final boolean triggered;
        private final String rationale;
        private final Instant decidedAt;

        private ActionStatus(String id, boolean recommended, boolean triggered, String rationale, Instant decidedAt) {
            this.id = id;
            this.recommended = recommended;
            this.triggered = triggered;
            this.rationale = rationale;
            this.decidedAt = decidedAt;
        }

        static ActionStatus create(String id, boolean recommended, boolean triggered, String rationale, Instant decidedAt) {
            return new ActionStatus(id, recommended, triggered, rationale, decidedAt);
        }

        String getId() {
            return id;
        }

        boolean isTriggered() {
            return triggered;
        }

        Map<String, Object> toMap(ActionDefinition definition) {
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

        GRuleStatus(String id, boolean triggered, String rationale) {
            this.id = id;
            this.triggered = triggered;
            this.rationale = rationale;
        }

        String getId() {
            return id;
        }

        boolean isTriggered() {
            return triggered;
        }

        Map<String, Object> toMap(GRuleDefinition definition) {
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
