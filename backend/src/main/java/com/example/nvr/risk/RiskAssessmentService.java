package com.example.nvr.risk;

import com.example.nvr.persistence.CameraAlarmEntity;
import com.example.nvr.persistence.CameraAlarmRepository;
import com.example.nvr.persistence.ImsiRecordEntity;
import com.example.nvr.persistence.ImsiRecordRepository;
import com.example.nvr.persistence.RadarTargetEntity;
import com.example.nvr.persistence.RadarTargetRepository;
import com.example.nvr.persistence.RiskAssessmentEntity;
import com.example.nvr.persistence.RiskAssessmentRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 风控评分与名单判定服务（v2.1）。
 *
 * <p>实现“系统架构 v2.1”中定义的 30 分钟滑窗计分、融合、白/黑名单逻辑，
 * 为风控页面提供统一的站点级风险结论。</p>
 */
@Service
public class RiskAssessmentService {

    private static final Logger log = LoggerFactory.getLogger(RiskAssessmentService.class);

    private static final Duration WINDOW = Duration.ofMinutes(30);
    private static final Duration HISTORY_LOOKBACK = Duration.ofDays(14);
    private static final Duration CASING_LOOKBACK = Duration.ofDays(7);
    private static final Duration RETURN_LOOKBACK = Duration.ofDays(14);

    private static final Duration BURST_MERGE = Duration.ofSeconds(90);
    private static final Duration SESSION_BREAK = Duration.ofMinutes(15);
    private static final Duration SESSION_LONG_GAP = Duration.ofMinutes(12);
    private static final Duration TIME_BUCKET = Duration.ofMinutes(5);
    private static final Duration ARRIVAL_WINDOW = Duration.ofMinutes(10);
    private static final Duration TIGHT_WINDOW = Duration.ofMinutes(5);
    private static final Duration ARRIVAL_HISTORY_WINDOW = ARRIVAL_WINDOW;
    private static final int ARRIVAL_HISTORY_SAMPLES = 6;

    private static final Duration CAMERA_SESSION_GAP = Duration.ofSeconds(120);
    private static final Duration CAMERA_REENTRY_WINDOW = Duration.ofMinutes(10);

    private static final Duration RADAR_TRACK_GAP = Duration.ofSeconds(20);
    private static final Duration RADAR_INTERMITTENT_WINDOW = Duration.ofSeconds(30);
    private static final double RADAR_NEAR_DISTANCE = 40.0;

    private static final int RADAR_SCORE_CAP = 20;
    private static final int FUSION_SCORE_CAP = 30;

    private static final ZoneId DEFAULT_ZONE = ZoneId.systemDefault();

    private static final String SUBJECT_TYPE = "SITE";
    private static final String SUBJECT_KEY = "DEFAULT";

    private final RiskAssessmentRepository riskAssessmentRepository;
    private final ImsiRecordRepository imsiRecordRepository;
    private final CameraAlarmRepository cameraAlarmRepository;
    private final RadarTargetRepository radarTargetRepository;
    private final ObjectMapper objectMapper;

    public RiskAssessmentService(RiskAssessmentRepository riskAssessmentRepository,
                                 ImsiRecordRepository imsiRecordRepository,
                                 CameraAlarmRepository cameraAlarmRepository,
                                 RadarTargetRepository radarTargetRepository,
                                 ObjectMapper objectMapper) {
        this.riskAssessmentRepository = riskAssessmentRepository;
        this.imsiRecordRepository = imsiRecordRepository;
        this.cameraAlarmRepository = cameraAlarmRepository;
        this.radarTargetRepository = radarTargetRepository;
        this.objectMapper = objectMapper;
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
        Instant windowStart = now.minus(WINDOW);
        Instant historyStart = now.minus(HISTORY_LOOKBACK);

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

        // 长窗口用于基线、重返判定。
        RiskAssessmentEntity previous = riskAssessmentRepository
                .findFirstBySubjectTypeAndSubjectKey(SUBJECT_TYPE, SUBJECT_KEY)
                .orElse(null);

        ScoreAccumulator score = new ScoreAccumulator();
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("windowStart", windowStart);
        metadata.put("windowEnd", now);

        // 统一时间带判定
        List<Instant> allInstants = new ArrayList<>();
        imsiWindow.stream().map(ImsiRecordEntity::getFetchedAt).filter(Objects::nonNull).forEach(allInstants::add);
        cameraWindow.stream().map(CameraAlarmEntity::getCreatedAt).filter(Objects::nonNull).forEach(allInstants::add);
        radarWindow.stream().map(RadarTargetEntity::getCapturedAt).filter(Objects::nonNull).forEach(allInstants::add);
        TimeBand dominantBand = determineDominantBand(allInstants);
        if (dominantBand == TimeBand.NIGHT) {
            score.addTimeScore("T_NIGHT", 25, "夜间活动（21:00–05:59）");
        } else if (dominantBand == TimeBand.DUSK) {
            score.addTimeScore("T_DUSK", 10, "黄昏活动（19:00–20:59）");
        } else {
            score.addTimeScore("T_DAY", 0, "白天活动（06:00–18:59）");
        }
        metadata.put("timeBand", dominantBand.name());

        // IMSI 预处理
        ImsiContext imsiContext = buildImsiContext(imsiHistory, windowStart, now);
        metadata.put("imsi", imsiContext.toMetadata());
        score.mergeImsi(imsiContext.getScoreHits());

        // 摄像头
        CameraContext cameraContext = buildCameraContext(cameraWindow);
        metadata.put("camera", cameraContext.toMetadata());
        score.mergeCamera(cameraContext.getScoreHits());

        // 雷达
        RadarContext radarContext = buildRadarContext(radarWindow);
        metadata.put("radar", radarContext.toMetadata());
        score.mergeRadar(radarContext.getScoreHits());

        // 白天踩点 → 夜返标记
        DayCasingState casingState = computeDayCasingState(imsiHistory, cameraHistory, now);
        metadata.put("dayCasing", casingState.toMetadata());
        if (casingState.isMarkActive() && dominantBand == TimeBand.NIGHT) {
            score.addBaseline("CASE_BASELINE", 10, "踩点标记后夜窗基线 +10");
        }
        if (casingState.shouldDirectBlackOnNightReturn() && cameraContext.isEnteredAoi() && cameraContext.isNightWindow()) {
            score.markDirectBlack("BLACK_CASING_RETURN", "白天踩点后 14 天内夜间进入 AOI");
        }

        // IMSI 夜外圈强警（3.3）
        if (dominantBand == TimeBand.NIGHT && imsiContext.isNightOuterDwell() && !cameraContext.isEnteredAoi()) {
            score.forceStrongAlert("STRONG_OUTER_DWELL", "夜间外圈 ≥3 桶且未入 AOI");
        }

        // 融合
        FusionContext fusionContext = buildFusionContext(imsiContext, cameraContext, radarContext, previous, now);
        metadata.put("fusion", fusionContext.toMetadata());
        score.mergeFusion(fusionContext.getScoreHits());
        if (fusionContext.isNightGroupInAoi()) {
            score.markDirectBlack("BLACK_NIGHT_GROUP", "夜间 AOI ≥2 人");
        }
        if (cameraContext.isNightWindow() && cameraContext.isNightReentry()) {
            score.markDirectBlack("BLACK_NIGHT_REENTRY", "夜间 10 分钟内进出再进 AOI");
        }
        if (cameraContext.isNightWindow() && cameraContext.isNightLongStay()) {
            score.markDirectBlack("BLACK_NIGHT_LONG_STAY", "夜间 AOI 停留 ≥180s");
        }

        String classification = classify(score);
        String summary = buildSummary(classification, score.getTotalScore(), dominantBand, score.getTopRuleDescription());

        persistAssessment(now, windowStart, score, metadata, classification, summary);
    }

    private ImsiContext buildImsiContext(List<ImsiRecordEntity> history,
                                         Instant windowStart,
                                         Instant windowEnd) {
        Map<String, List<ImsiRecordEntity>> grouped = history.stream()
                .filter(Objects::nonNull)
                .filter(rec -> rec.getFetchedAt() != null)
                .filter(rec -> hasText(rec.getImsi()) || hasText(rec.getDeviceId()))
                .collect(Collectors.groupingBy(this::imsiKey));

        List<ImsiDeviceMetrics> metrics = new ArrayList<>();
        Map<String, Instant> firstSeen = new HashMap<>();

        for (Map.Entry<String, List<ImsiRecordEntity>> entry : grouped.entrySet()) {
            List<ImsiRecordEntity> bursts = mergeBursts(entry.getValue());
            if (bursts.isEmpty()) {
                continue;
            }
            List<ImsiSession> sessions = buildImsiSessions(bursts);
            ImsiDeviceMetrics deviceMetrics = new ImsiDeviceMetrics(entry.getKey(), bursts, sessions, windowStart, windowEnd);
            metrics.add(deviceMetrics);
            firstSeen.put(entry.getKey(), bursts.get(0).getFetchedAt());
        }

        metrics.sort(Comparator.comparingInt(ImsiDeviceMetrics::getScore).reversed());
        ImsiDeviceMetrics top = metrics.isEmpty() ? null : metrics.get(0);

        int arrivals10 = countArrivals(firstSeen, windowEnd.minus(ARRIVAL_WINDOW), windowEnd);
        int arrivalsTight = countArrivals(firstSeen, windowEnd.minus(TIGHT_WINDOW), windowEnd);
        List<Integer> arrivalHistory = computeArrivalHistory(firstSeen, windowEnd);
        double arrivalZ = computeZScore(arrivals10, arrivalHistory);
        double baselineMean = arrivalHistory.stream().mapToInt(Integer::intValue).average().orElse(0.0);
        boolean highDetect = baselineMean >= 2.0 && arrivalHistory.size() >= 3;

        List<RuleHit> hits = new ArrayList<>();
        if (top != null) {
            hits.addAll(top.getRuleHits());
        }

        boolean nightOuterDwell = top != null && top.isNightWindow() && top.getBucketCount() >= 3;

        return new ImsiContext(metrics, top, arrivals10, arrivalsTight, arrivalZ, arrivalHistory,
                highDetect, nightOuterDwell, hits);
    }

    private List<ImsiRecordEntity> mergeBursts(List<ImsiRecordEntity> records) {
        List<ImsiRecordEntity> sorted = records.stream()
                .filter(Objects::nonNull)
                .filter(it -> it.getFetchedAt() != null)
                .sorted(Comparator.comparing(ImsiRecordEntity::getFetchedAt))
                .collect(Collectors.toList());
        if (sorted.isEmpty()) {
            return Collections.emptyList();
        }
        List<ImsiRecordEntity> bursts = new ArrayList<>();
        ImsiRecordEntity last = null;
        for (ImsiRecordEntity record : sorted) {
            if (last == null || Duration.between(last.getFetchedAt(), record.getFetchedAt()).compareTo(BURST_MERGE) > 0) {
                bursts.add(record);
                last = record;
            }
        }
        return bursts;
    }

    private List<ImsiSession> buildImsiSessions(List<ImsiRecordEntity> bursts) {
        List<ImsiSession> sessions = new ArrayList<>();
        ImsiSession current = null;
        ImsiRecordEntity previous = null;
        for (ImsiRecordEntity record : bursts) {
            Instant ts = record.getFetchedAt();
            if (ts == null) {
                continue;
            }
            if (previous == null) {
                current = new ImsiSession();
                current.add(record);
                sessions.add(current);
            } else {
                Duration gap = Duration.between(previous.getFetchedAt(), ts);
                if (gap.compareTo(SESSION_BREAK) > 0) {
                    current = new ImsiSession();
                    current.add(record);
                    sessions.add(current);
                } else {
                    current.add(record);
                    if (gap.compareTo(SESSION_LONG_GAP) > 0) {
                        current.setHasLongGapWithinSession(true);
                    }
                }
            }
            previous = record;
        }
        return sessions;
    }

    private CameraContext buildCameraContext(List<CameraAlarmEntity> window) {
        List<Instant> timestamps = window.stream()
                .map(CameraAlarmEntity::getCreatedAt)
                .filter(Objects::nonNull)
                .sorted()
                .collect(Collectors.toList());
        TimeBand band = determineDominantBand(timestamps);

        List<CameraSession> sessions = buildCameraSessions(timestamps);

        boolean entered = !timestamps.isEmpty();
        boolean stayOver60 = sessions.stream().anyMatch(session -> session.getDurationSeconds() >= 60);
        boolean stayOver180 = sessions.stream().anyMatch(session -> session.getDurationSeconds() >= 180);
        boolean reentry = false;
        for (int i = 1; i < sessions.size(); i++) {
            CameraSession prev = sessions.get(i - 1);
            CameraSession curr = sessions.get(i);
            if (prev.getEnd() != null && curr.getStart() != null) {
                Duration gap = Duration.between(prev.getEnd(), curr.getStart());
                if (!gap.isNegative() && gap.compareTo(CAMERA_REENTRY_WINDOW) <= 0) {
                    reentry = true;
                    break;
                }
            }
        }

        long edgeLoopCount = window.stream()
                .map(CameraAlarmEntity::getEventType)
                .filter(Objects::nonNull)
                .map(String::toLowerCase)
                .filter(type -> type.contains("edge") || type.contains("perimeter") || type.contains("loop"))
                .count();

        boolean multiPersonHint = window.stream()
                .map(CameraAlarmEntity::getLevel)
                .filter(Objects::nonNull)
                .map(String::toLowerCase)
                .anyMatch(level -> level.contains("multi") || level.contains("group") || level.contains("crowd"));
        if (!multiPersonHint) {
            multiPersonHint = window.stream()
                    .map(CameraAlarmEntity::getEventType)
                    .filter(Objects::nonNull)
                    .map(String::toLowerCase)
                    .anyMatch(type -> type.contains("multi") || type.contains("group"));
        }

        List<RuleHit> hits = new ArrayList<>();
        if (entered) {
            if (band == TimeBand.NIGHT) {
                hits.add(new RuleHit("C_ENTER_NIGHT", 25, "夜间进入 AOI"));
            } else if (band == TimeBand.DUSK) {
                hits.add(new RuleHit("C_ENTER_DUSK", 10, "黄昏进入 AOI"));
            } else {
                hits.add(new RuleHit("C_ENTER_DAY", 10, "白天进入 AOI"));
            }
        }
        if (stayOver60) {
            if (band == TimeBand.NIGHT) {
                hits.add(new RuleHit("C_STAY_NIGHT", 15, "夜间 AOI 停留 >60s"));
            } else {
                hits.add(new RuleHit("C_STAY_DAY", 8, "白天 AOI 停留 >60s"));
            }
        }
        if (stayOver180 && band == TimeBand.NIGHT) {
            hits.add(new RuleHit("C_STAY_NIGHT_180", 10, "夜间 AOI 停留 >180s"));
        }
        if (reentry) {
            if (band == TimeBand.NIGHT) {
                hits.add(new RuleHit("C_REENTRY_NIGHT", 12, "夜间 10 分钟内进出再进"));
            } else {
                hits.add(new RuleHit("C_REENTRY_DAY", 6, "白天 10 分钟内进出再进"));
            }
        }
        if (edgeLoopCount >= 2) {
            if (band == TimeBand.NIGHT) {
                hits.add(new RuleHit("C_EDGE_NIGHT", 10, "夜间 AOI 边缘绕行 ≥2 次"));
            } else {
                hits.add(new RuleHit("C_EDGE_DAY", 6, "白天 AOI 边缘绕行 ≥2 次"));
            }
        }

        boolean nightWindow = band == TimeBand.NIGHT;

        return new CameraContext(entered, stayOver180, reentry, multiPersonHint, nightWindow,
                edgeLoopCount, hits, sessions, band);
    }

    private List<CameraSession> buildCameraSessions(List<Instant> timestamps) {
        List<CameraSession> sessions = new ArrayList<>();
        CameraSession current = null;
        Instant previous = null;
        for (Instant ts : timestamps) {
            if (previous == null) {
                current = new CameraSession(ts, ts);
                sessions.add(current);
            } else {
                Duration gap = Duration.between(previous, ts);
                if (gap.compareTo(CAMERA_SESSION_GAP) > 0) {
                    current = new CameraSession(ts, ts);
                    sessions.add(current);
                } else {
                    current.setEnd(ts);
                }
            }
            previous = ts;
        }
        return sessions;
    }

    private RadarContext buildRadarContext(List<RadarTargetEntity> window) {
        if (window.isEmpty()) {
            return RadarContext.empty();
        }
        Map<String, List<RadarTargetEntity>> grouped = window.stream()
                .filter(it -> it.getCapturedAt() != null)
                .filter(it -> hasText(it.getRadarHost()) && it.getTargetId() != null)
                .collect(Collectors.groupingBy(it -> it.getRadarHost() + "#" + it.getTargetId()));

        List<RadarTrack> tracks = new ArrayList<>();
        for (List<RadarTargetEntity> events : grouped.values()) {
            List<RadarTargetEntity> sorted = events.stream()
                    .sorted(Comparator.comparing(RadarTargetEntity::getCapturedAt))
                    .collect(Collectors.toList());
            tracks.add(new RadarTrack(sorted));
        }

        TimeBand band = determineDominantBand(window.stream()
                .map(RadarTargetEntity::getCapturedAt)
                .filter(Objects::nonNull)
                .collect(Collectors.toList()));

        boolean anyTarget = !tracks.isEmpty();
        boolean continuous = tracks.stream().anyMatch(track -> track.getDuration().getSeconds() >= 60);
        boolean approachDisappear = tracks.stream().anyMatch(RadarTrack::isApproachAndDisappear);
        boolean intermittent = tracks.stream().anyMatch(RadarTrack::isIntermittent);
        int approachTracks = (int) tracks.stream().filter(RadarTrack::hasNearApproach).count();

        boolean multiTarget = false;
        if (tracks.size() >= 2) {
            for (int i = 0; i < tracks.size(); i++) {
                for (int j = i + 1; j < tracks.size(); j++) {
                    if (tracks.get(i).overlaps(tracks.get(j))) {
                        multiTarget = true;
                        break;
                    }
                }
                if (multiTarget) {
                    break;
                }
            }
        }

        List<RuleHit> hits = new ArrayList<>();
        if (anyTarget) {
            hits.add(new RuleHit("R_TARGET", band == TimeBand.NIGHT ? 2 : 1, "雷达检测到目标"));
        }
        if (continuous) {
            hits.add(new RuleHit("R_PERSIST", 1, "目标持续 ≥60s"));
        }
        if (approachDisappear) {
            hits.add(new RuleHit("R_APPROACH", band == TimeBand.NIGHT ? 3 : 2, "逼近后消失"));
        }
        if (multiTarget) {
            hits.add(new RuleHit("R_MULTI", band == TimeBand.NIGHT ? 2 : 1, "多目标同步"));
        }
        if (intermittent) {
            hits.add(new RuleHit("R_INTERMITTENT", band == TimeBand.NIGHT ? 2 : 1, "间歇潜行"));
        }

        return new RadarContext(hits, approachDisappear, approachTracks, band);
    }

    private DayCasingState computeDayCasingState(List<ImsiRecordEntity> imsiHistory,
                                                 List<CameraAlarmEntity> cameraHistory,
                                                 Instant now) {
        Instant casingStart = now.minus(CASING_LOOKBACK);
        Instant returnWindowStart = now.minus(HISTORY_LOOKBACK);

        Map<LocalDate, Boolean> dayBuckets = new HashMap<>();
        Map<String, List<ImsiRecordEntity>> grouped = imsiHistory.stream()
                .filter(it -> it.getFetchedAt() != null)
                .filter(it -> !it.getFetchedAt().isBefore(casingStart))
                .filter(it -> determineBand(it.getFetchedAt()) != TimeBand.NIGHT)
                .filter(it -> hasText(it.getImsi()) || hasText(it.getDeviceId()))
                .collect(Collectors.groupingBy(this::imsiKey));
        for (List<ImsiRecordEntity> records : grouped.values()) {
            List<ImsiRecordEntity> bursts = mergeBursts(records);
            List<ImsiSession> sessions = buildImsiSessions(bursts);
            for (ImsiSession session : sessions) {
                if (session.getStart() == null) {
                    continue;
                }
                if (session.getStart().isBefore(casingStart)) {
                    continue;
                }
                if (determineBand(session.getStart()) == TimeBand.NIGHT) {
                    continue;
                }
                long minutes = session.getDurationMinutes();
                if (minutes >= 10) {
                    LocalDate date = LocalDateTime.ofInstant(session.getStart(), DEFAULT_ZONE).toLocalDate();
                    dayBuckets.put(date, Boolean.TRUE);
                }
            }
        }

        long casingDays = dayBuckets.keySet().stream()
                .filter(date -> !date.isBefore(LocalDateTime.ofInstant(casingStart, DEFAULT_ZONE).toLocalDate()))
                .count();

        Instant latestCasing = dayBuckets.keySet().stream()
                .map(date -> date.atStartOfDay(DEFAULT_ZONE).toInstant())
                .max(Comparator.naturalOrder())
                .orElse(null);

        boolean markActive = latestCasing != null && Duration.between(latestCasing, now).compareTo(RETURN_LOOKBACK) <= 0;

        boolean nightEntry = cameraHistory.stream()
                .filter(it -> it.getCreatedAt() != null)
                .filter(it -> !it.getCreatedAt().isAfter(now))
                .filter(it -> !it.getCreatedAt().isBefore(returnWindowStart))
                .filter(it -> determineBand(it.getCreatedAt()) == TimeBand.NIGHT)
                .anyMatch(it -> latestCasing != null && !it.getCreatedAt().isBefore(latestCasing));

        return new DayCasingState(markActive, nightEntry, latestCasing, casingDays >= 2);
    }

    private FusionContext buildFusionContext(ImsiContext imsiContext,
                                             CameraContext cameraContext,
                                             RadarContext radarContext,
                                             RiskAssessmentEntity previous,
                                             Instant now) {
        List<RuleHit> hits = new ArrayList<>();

        List<Instant> history = previous == null ? new ArrayList<>() : extractF3F4History(previous.getDetailsJson());
        history = history.stream()
                .filter(Objects::nonNull)
                .filter(inst -> Duration.between(inst, now).compareTo(Duration.ofDays(7)) <= 0)
                .collect(Collectors.toCollection(ArrayList::new));

        boolean isNight = cameraContext.isNightWindow();
        boolean f3Triggered = false;
        boolean f4Triggered = false;

        if (cameraContext.isEnteredAoi() && (imsiContext.getArrivals10() >= 2 || imsiContext.getArrivalZ() >= 2.0)) {
            hits.add(new RuleHit("F1", isNight ? 15 : 8, "F1 协同到达"));
        }
        if (cameraContext.isEnteredAoi() && imsiContext.getArrivalsTight() == 0 && imsiContext.isHighDetectability()) {
            hits.add(new RuleHit("F2", isNight ? 12 : 2, "F2 无手机嫌疑"));
        }
        if (isNight && cameraContext.isMultiPersonHint() && imsiContext.getArrivalsTight() >= 2) {
            hits.add(new RuleHit("F3", 15, "F3 夜间成伙"));
            f3Triggered = true;
        }
        if (isNight && imsiContext.getHighRiskDeviceCount() >= 2 && radarContext.getApproachTracks() >= 2) {
            hits.add(new RuleHit("F4", 15, "F4 夜间多人外圈协同"));
            f4Triggered = true;
        }
        if (f3Triggered || f4Triggered) {
            history.add(now);
        }
        if (history.size() >= 2) {
            hits.add(new RuleHit("F5", 6, "F5 固定搭档"));
        }
        if (isNight && imsiContext.getArrivalZ() >= 3.0 && radarContext.isApproachDisappear()) {
            hits.add(new RuleHit("F6", 10, "F6 夜间异常汇聚"));
        }

        boolean nightGroup = isNight && cameraContext.isMultiPersonHint();

        return new FusionContext(hits, imsiContext.getArrivals10(), imsiContext.getArrivalsTight(),
                imsiContext.getArrivalZ(), imsiContext.isHighDetectability(), history, nightGroup);
    }

    private List<Instant> extractF3F4History(String json) {
        if (!hasText(json)) {
            return new ArrayList<>();
        }
        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode metadata = root.path("metadata");
            JsonNode fusion = metadata.path("fusion");
            JsonNode historyNode = fusion.path("f3f4History");
            if (!historyNode.isArray()) {
                return new ArrayList<>();
            }
            List<String> values = objectMapper.convertValue(historyNode, new TypeReference<List<String>>() {
            });
            return values.stream()
                    .filter(Objects::nonNull)
                    .map(this::parseInstant)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toCollection(ArrayList::new));
        } catch (Exception e) {
            log.debug("Failed to parse previous fusion history", e);
            return new ArrayList<>();
        }
    }

    private Instant parseInstant(String value) {
        try {
            return Instant.parse(value);
        } catch (Exception e) {
            return null;
        }
    }

    private void persistAssessment(Instant now,
                                   Instant windowStart,
                                   ScoreAccumulator score,
                                   Map<String, Object> metadata,
                                   String classification,
                                   String summary) {
        RiskAssessmentEntity entity = riskAssessmentRepository
                .findFirstBySubjectTypeAndSubjectKey(SUBJECT_TYPE, SUBJECT_KEY)
                .orElseGet(() -> new RiskAssessmentEntity(SUBJECT_TYPE, SUBJECT_KEY));
        entity.setSubjectType(SUBJECT_TYPE);
        entity.setSubjectKey(SUBJECT_KEY);
        entity.setScore(score.getTotalScore());
        entity.setClassification(classification);
        entity.setSummary(summary);
        entity.setWindowStart(windowStart);
        entity.setWindowEnd(now);
        entity.setUpdatedAt(now);
        entity.setDetailsJson(writeDetailsJson(score, metadata));
        riskAssessmentRepository.save(entity);
    }

    private String classify(ScoreAccumulator score) {
        if (!score.getDirectBlackRules().isEmpty()) {
            return "BLACK";
        }
        if (score.getTotalScore() >= 70) {
            return "BLACK";
        }
        if (score.getTotalScore() >= 55 || score.isForceStrongAlert()) {
            return "STRONG_ALERT";
        }
        if (score.getTotalScore() >= 30) {
            return "GRAY";
        }
        return "LOG_ONLY";
    }

    private String buildSummary(String classification,
                                int totalScore,
                                TimeBand band,
                                String topRule) {
        String label;
        switch (classification) {
            case "BLACK":
                label = "黑名单";
                break;
            case "STRONG_ALERT":
                label = "强警戒";
                break;
            case "GRAY":
                label = "灰名单";
                break;
            default:
                label = "仅记录";
        }
        StringBuilder sb = new StringBuilder(label)
                .append(" | ")
                .append(totalScore)
                .append(" 分")
                .append(" | ")
                .append(band == TimeBand.NIGHT ? "夜间窗" : band == TimeBand.DUSK ? "黄昏窗" : "白天窗");
        if (hasText(topRule)) {
            sb.append(" | ").append(topRule);
        }
        return sb.toString();
    }

    private String writeDetailsJson(ScoreAccumulator score, Map<String, Object> metadata) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("scoreHits", score.getScoreHits());
        payload.put("directBlack", score.getDirectBlackRules());
        payload.put("strongAlert", score.getStrongAlertRules());
        payload.put("metadata", metadata);
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize risk assessment details", e);
            return "{}";
        }
    }

    private static TimeBand determineBand(Instant instant) {
        if (instant == null) {
            return TimeBand.DAY;
        }
        LocalTime time = LocalDateTime.ofInstant(instant, DEFAULT_ZONE).toLocalTime();
        if (!time.isBefore(LocalTime.of(6, 0)) && !time.isAfter(LocalTime.of(18, 59))) {
            return TimeBand.DAY;
        }
        if (!time.isBefore(LocalTime.of(19, 0)) && !time.isAfter(LocalTime.of(20, 59))) {
            return TimeBand.DUSK;
        }
        return TimeBand.NIGHT;
    }

    private static TimeBand determineDominantBand(List<Instant> instants) {
        if (instants == null || instants.isEmpty()) {
            return TimeBand.DAY;
        }
        EnumMap<TimeBand, Long> counter = new EnumMap<>(TimeBand.class);
        for (Instant instant : instants) {
            TimeBand band = determineBand(instant);
            counter.merge(band, 1L, Long::sum);
        }
        return counter.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(TimeBand.DAY);
    }

    private int countArrivals(Map<String, Instant> firstSeen, Instant start, Instant end) {
        if (firstSeen.isEmpty() || start == null || end == null) {
            return 0;
        }
        return (int) firstSeen.values().stream()
                .filter(Objects::nonNull)
                .filter(ts -> !ts.isBefore(start) && !ts.isAfter(end))
                .count();
    }

    private List<Integer> computeArrivalHistory(Map<String, Instant> firstSeen, Instant anchor) {
        if (firstSeen.isEmpty() || anchor == null) {
            return Collections.emptyList();
        }
        List<Integer> samples = new ArrayList<>();
        Instant windowEnd = anchor.minusMillis(1);
        for (int i = 0; i < ARRIVAL_HISTORY_SAMPLES; i++) {
            Instant windowStart = windowEnd.minus(ARRIVAL_HISTORY_WINDOW);
            samples.add(countArrivals(firstSeen, windowStart, windowEnd));
            windowEnd = windowStart.minusMillis(1);
        }
        Collections.reverse(samples);
        return samples;
    }

    private double computeZScore(int current, List<Integer> history) {
        if (history == null || history.size() < 3) {
            return 0.0;
        }
        double mean = history.stream().mapToInt(Integer::intValue).average().orElse(0);
        double variance = history.stream()
                .mapToDouble(value -> Math.pow(value - mean, 2))
                .average()
                .orElse(0);
        double std = Math.sqrt(variance);
        if (std < 1e-6) {
            return 0.0;
        }
        return (current - mean) / std;
    }

    private String imsiKey(ImsiRecordEntity record) {
        if (record == null) {
            return null;
        }
        if (hasText(record.getImsi())) {
            return record.getImsi();
        }
        return record.getDeviceId();
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private enum TimeBand {
        DAY,
        DUSK,
        NIGHT
    }

    private static class ScoreAccumulator {
        private final List<RuleHit> scoreHits = new ArrayList<>();
        private final List<RuleHit> directBlackRules = new ArrayList<>();
        private final List<RuleHit> strongAlertRules = new ArrayList<>();
        private int timeScore;
        private int imsiScore;
        private int radarScore;
        private int cameraScore;
        private int fusionScore;
        private int baselineScore;
        private boolean forceStrongAlert;

        void addTimeScore(String id, int value, String description) {
            if (value <= 0) {
                return;
            }
            timeScore += value;
            scoreHits.add(new RuleHit(id, value, description));
        }

        void mergeImsi(List<RuleHit> hits) {
            for (RuleHit hit : hits) {
                imsiScore += hit.getScore();
                scoreHits.add(hit);
            }
        }

        void mergeCamera(List<RuleHit> hits) {
            for (RuleHit hit : hits) {
                cameraScore += hit.getScore();
                scoreHits.add(hit);
            }
        }

        void mergeRadar(List<RuleHit> hits) {
            for (RuleHit hit : hits) {
                int allowed = Math.min(hit.getScore(), RADAR_SCORE_CAP - radarScore);
                if (allowed <= 0) {
                    continue;
                }
                radarScore += allowed;
                scoreHits.add(new RuleHit(hit.getId(), allowed, hit.getDescription()));
            }
        }

        void mergeFusion(List<RuleHit> hits) {
            for (RuleHit hit : hits) {
                int allowed = Math.min(hit.getScore(), FUSION_SCORE_CAP - fusionScore);
                if (allowed <= 0) {
                    continue;
                }
                fusionScore += allowed;
                scoreHits.add(new RuleHit(hit.getId(), allowed, hit.getDescription()));
            }
        }

        void addBaseline(String id, int value, String description) {
            if (value <= 0) {
                return;
            }
            baselineScore += value;
            scoreHits.add(new RuleHit(id, value, description));
        }

        void markDirectBlack(String id, String description) {
            directBlackRules.add(new RuleHit(id, 0, description));
        }

        void forceStrongAlert(String id, String description) {
            forceStrongAlert = true;
            strongAlertRules.add(new RuleHit(id, 0, description));
        }

        int getTotalScore() {
            return timeScore + imsiScore + radarScore + cameraScore + fusionScore + baselineScore;
        }

        List<RuleHit> getScoreHits() {
            return scoreHits;
        }

        List<RuleHit> getDirectBlackRules() {
            return directBlackRules;
        }

        List<RuleHit> getStrongAlertRules() {
            return strongAlertRules;
        }

        boolean isForceStrongAlert() {
            return forceStrongAlert;
        }

        String getTopRuleDescription() {
            return scoreHits.isEmpty() ? null : scoreHits.get(0).getDescription();
        }
    }

    private static class RuleHit {
        private final String id;
        private final int score;
        private final String description;

        RuleHit(String id, int score, String description) {
            this.id = id;
            this.score = score;
            this.description = description;
        }

        public String getId() {
            return id;
        }

        public int getScore() {
            return score;
        }

        public String getDescription() {
            return description;
        }
    }

    private static class ImsiContext {
        private final List<ImsiDeviceMetrics> devices;
        private final ImsiDeviceMetrics topDevice;
        private final int arrivals10;
        private final int arrivalsTight;
        private final double arrivalZ;
        private final List<Integer> arrivalHistory;
        private final boolean highDetectability;
        private final boolean nightOuterDwell;
        private final List<RuleHit> scoreHits;

        ImsiContext(List<ImsiDeviceMetrics> devices,
                    ImsiDeviceMetrics topDevice,
                    int arrivals10,
                    int arrivalsTight,
                    double arrivalZ,
                    List<Integer> arrivalHistory,
                    boolean highDetectability,
                    boolean nightOuterDwell,
                    List<RuleHit> scoreHits) {
            this.devices = devices;
            this.topDevice = topDevice;
            this.arrivals10 = arrivals10;
            this.arrivalsTight = arrivalsTight;
            this.arrivalZ = arrivalZ;
            this.arrivalHistory = arrivalHistory;
            this.highDetectability = highDetectability;
            this.nightOuterDwell = nightOuterDwell;
            this.scoreHits = scoreHits;
        }

        Map<String, Object> toMetadata() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("devices", devices.stream().map(ImsiDeviceMetrics::toMetadata).collect(Collectors.toList()));
            map.put("topDevice", topDevice != null ? topDevice.toMetadata() : null);
            map.put("arrivals10", arrivals10);
            map.put("arrivalsTight", arrivalsTight);
            map.put("arrivalZ", arrivalZ);
            map.put("arrivalHistory", arrivalHistory);
            map.put("highDetectability", highDetectability);
            map.put("nightOuterDwell", nightOuterDwell);
            map.put("highRiskDeviceCount", getHighRiskDeviceCount());
            return map;
        }

        int getArrivals10() {
            return arrivals10;
        }

        int getArrivalsTight() {
            return arrivalsTight;
        }

        double getArrivalZ() {
            return arrivalZ;
        }

        boolean isHighDetectability() {
            return highDetectability;
        }

        boolean isNightOuterDwell() {
            return nightOuterDwell;
        }

        int getHighRiskDeviceCount() {
            return (int) devices.stream().filter(d -> d.getBucketCount() >= 2).count();
        }

        List<RuleHit> getScoreHits() {
            return scoreHits;
        }

        ImsiDeviceMetrics getTopDevice() {
            return topDevice;
        }
    }

    private static class ImsiDeviceMetrics {
        private final String key;
        private final int bucketCount;
        private final boolean hasLongGap;
        private final boolean hasRevisit;
        private final TimeBand dominantBand;
        private final int score;
        private final List<RuleHit> ruleHits;
        private final Instant start;
        private final Instant end;

        ImsiDeviceMetrics(String key,
                          List<ImsiRecordEntity> bursts,
                          List<ImsiSession> sessions,
                          Instant windowStart,
                          Instant windowEnd) {
            this.key = key;
            this.start = bursts.isEmpty() ? null : bursts.get(0).getFetchedAt();
            this.end = bursts.isEmpty() ? null : bursts.get(bursts.size() - 1).getFetchedAt();
            List<Instant> windowBursts = bursts.stream()
                    .map(ImsiRecordEntity::getFetchedAt)
                    .filter(ts -> ts != null && !ts.isBefore(windowStart) && !ts.isAfter(windowEnd))
                    .collect(Collectors.toList());
            this.bucketCount = new LinkedHashSet<>(windowBursts.stream()
                    .map(instant -> truncateToBucket(instant))
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList())).size();
            this.hasLongGap = sessions.stream().anyMatch(ImsiSession::hasLongGapWithinSession);
            this.hasRevisit = hasRevisit(sessions);
            this.dominantBand = determineDominantBand(windowBursts);
            this.ruleHits = new ArrayList<>();
            int total = 0;
            if (bucketCount == 1) {
                ruleHits.add(new RuleHit("B1", 2, key + " 命中 1 桶"));
                total += 2;
            } else if (bucketCount == 2) {
                ruleHits.add(new RuleHit("B2", 8, key + " 命中 2 桶"));
                total += 8;
            } else if (bucketCount == 3) {
                ruleHits.add(new RuleHit("B3", 14, key + " 命中 3 桶"));
                total += 14;
            } else if (bucketCount >= 4) {
                ruleHits.add(new RuleHit("B4", 20, key + " 命中 ≥4 桶"));
                total += 20;
            }
            if (hasLongGap) {
                ruleHits.add(new RuleHit("C1", 4, key + " 会话内 ≥12min 间隔"));
                total += 4;
            }
            if (hasRevisit) {
                ruleHits.add(new RuleHit("C2", 6, key + " 15min+ 断开后 2h 内重返"));
                total += 6;
            }
            this.score = total;
        }

        private boolean hasRevisit(List<ImsiSession> sessions) {
            if (sessions.size() < 2) {
                return false;
            }
            for (int i = 1; i < sessions.size(); i++) {
                ImsiSession prev = sessions.get(i - 1);
                ImsiSession curr = sessions.get(i);
                if (prev.getEnd() != null && curr.getStart() != null) {
                    long gapMinutes = Duration.between(prev.getEnd(), curr.getStart()).toMinutes();
                    if (gapMinutes > SESSION_BREAK.toMinutes() && gapMinutes <= Duration.ofHours(2).toMinutes()) {
                        return true;
                    }
                }
            }
            return false;
        }

        Map<String, Object> toMetadata() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("key", key);
            map.put("bucketCount", bucketCount);
            map.put("hasLongGap", hasLongGap);
            map.put("hasRevisit", hasRevisit);
            map.put("score", score);
            map.put("dominantBand", dominantBand.name());
            map.put("start", start);
            map.put("end", end);
            return map;
        }

        int getBucketCount() {
            return bucketCount;
        }

        TimeBand getDominantBand() {
            return dominantBand;
        }

        int getScore() {
            return score;
        }

        boolean isNightWindow() {
            return dominantBand == TimeBand.NIGHT;
        }

        List<RuleHit> getRuleHits() {
            return ruleHits;
        }
    }

    private static Instant truncateToBucket(Instant instant) {
        if (instant == null) {
            return null;
        }
        long epoch = instant.getEpochSecond();
        long bucket = Math.floorDiv(epoch, TIME_BUCKET.getSeconds()) * TIME_BUCKET.getSeconds();
        return Instant.ofEpochSecond(bucket);
    }

    private static class ImsiSession {
        private final List<ImsiRecordEntity> records = new ArrayList<>();
        private boolean hasLongGapWithinSession;

        void add(ImsiRecordEntity record) {
            records.add(record);
        }

        Instant getStart() {
            return records.isEmpty() ? null : records.get(0).getFetchedAt();
        }

        Instant getEnd() {
            return records.isEmpty() ? null : records.get(records.size() - 1).getFetchedAt();
        }

        long getDurationMinutes() {
            if (records.isEmpty()) {
                return 0;
            }
            Instant start = getStart();
            Instant end = getEnd();
            if (start == null || end == null) {
                return 0;
            }
            return Math.max(0, Duration.between(start, end).toMinutes());
        }

        boolean hasLongGapWithinSession() {
            return hasLongGapWithinSession;
        }

        void setHasLongGapWithinSession(boolean value) {
            this.hasLongGapWithinSession = value;
        }
    }

    private static class CameraSession {
        private final Instant start;
        private Instant end;

        CameraSession(Instant start, Instant end) {
            this.start = start;
            this.end = end;
        }

        Instant getStart() {
            return start;
        }

        Instant getEnd() {
            return end;
        }

        void setEnd(Instant end) {
            this.end = end;
        }

        long getDurationSeconds() {
            if (start == null || end == null) {
                return 0;
            }
            return Math.max(0, Duration.between(start, end).getSeconds());
        }
    }

    private static class CameraContext {
        private final boolean enteredAoi;
        private final boolean nightLongStay;
        private final boolean nightReentry;
        private final boolean multiPersonHint;
        private final boolean nightWindow;
        private final long edgeLoopCount;
        private final List<RuleHit> scoreHits;
        private final List<CameraSession> sessions;
        private final TimeBand band;

        CameraContext(boolean enteredAoi,
                      boolean nightLongStay,
                      boolean nightReentry,
                      boolean multiPersonHint,
                      boolean nightWindow,
                      long edgeLoopCount,
                      List<RuleHit> scoreHits,
                      List<CameraSession> sessions,
                      TimeBand band) {
            this.enteredAoi = enteredAoi;
            this.nightLongStay = nightLongStay;
            this.nightReentry = nightReentry;
            this.multiPersonHint = multiPersonHint;
            this.nightWindow = nightWindow;
            this.edgeLoopCount = edgeLoopCount;
            this.scoreHits = scoreHits;
            this.sessions = sessions;
            this.band = band;
        }

        Map<String, Object> toMetadata() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("enteredAoi", enteredAoi);
            map.put("nightLongStay", nightLongStay);
            map.put("nightReentry", nightReentry);
            map.put("multiPersonHint", multiPersonHint);
            map.put("nightWindow", nightWindow);
            map.put("edgeLoopCount", edgeLoopCount);
            List<Map<String, Object>> sessionMeta = new ArrayList<>();
            for (CameraSession session : sessions) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("start", session.getStart());
                item.put("end", session.getEnd());
                item.put("durationSeconds", session.getDurationSeconds());
                sessionMeta.add(item);
            }
            map.put("sessions", sessionMeta);
            map.put("band", band.name());
            return map;
        }

        boolean isEnteredAoi() {
            return enteredAoi;
        }

        boolean isNightWindow() {
            return nightWindow;
        }

        boolean isNightLongStay() {
            return nightLongStay;
        }

        boolean isNightReentry() {
            return nightReentry;
        }

        boolean isMultiPersonHint() {
            return multiPersonHint;
        }

        List<RuleHit> getScoreHits() {
            return scoreHits;
        }
    }

    private static class RadarTrack {
        private final List<RadarTargetEntity> events;
        private final Duration duration;
        private final boolean approachAndDisappear;
        private final boolean intermittent;
        private final boolean hasNearApproach;
        private final Instant start;
        private final Instant end;

        RadarTrack(List<RadarTargetEntity> events) {
            this.events = events;
            if (events.isEmpty()) {
                this.duration = Duration.ZERO;
                this.approachAndDisappear = false;
                this.intermittent = false;
                this.hasNearApproach = false;
                this.start = null;
                this.end = null;
                return;
            }
            this.start = events.get(0).getCapturedAt();
            this.end = events.get(events.size() - 1).getCapturedAt();
            this.duration = (start != null && end != null) ? Duration.between(start, end) : Duration.ZERO;

            boolean near = false;
            Instant lastNear = null;
            Instant previous = null;
            boolean intermittentFlag = false;
            for (RadarTargetEntity event : events) {
                Instant ts = event.getCapturedAt();
                if (ts == null) {
                    continue;
                }
                Double range = event.getRange();
                if (range == null) {
                    Double longitudinal = event.getLongitudinalDistance();
                    Double lateral = event.getLateralDistance();
                    if (longitudinal != null || lateral != null) {
                        double lon = longitudinal != null ? longitudinal : 0.0;
                        double lat = lateral != null ? lateral : 0.0;
                        range = Math.sqrt(lon * lon + lat * lat);
                    }
                }
                if (range != null && range <= RADAR_NEAR_DISTANCE) {
                    near = true;
                    lastNear = ts;
                }
                if (previous != null) {
                    Duration gap = Duration.between(previous, ts);
                    if (gap.compareTo(RADAR_INTERMITTENT_WINDOW) > 0) {
                        intermittentFlag = true;
                    }
                }
                previous = ts;
            }
            this.hasNearApproach = near;
            this.intermittent = intermittentFlag;
            if (near && lastNear != null) {
                final Instant finalLastNear = lastNear;
                boolean disappear = events.stream()
                        .map(RadarTargetEntity::getCapturedAt)
                        .filter(Objects::nonNull)
                        .noneMatch(ts -> !ts.equals(finalLastNear)
                                && !ts.isBefore(finalLastNear)
                                && Duration.between(finalLastNear, ts).compareTo(RADAR_INTERMITTENT_WINDOW) <= 0);
                this.approachAndDisappear = disappear;
            } else {
                this.approachAndDisappear = false;
            }
        }

        Duration getDuration() {
            return duration;
        }

        boolean isApproachAndDisappear() {
            return approachAndDisappear;
        }

        boolean isIntermittent() {
            return intermittent;
        }

        boolean hasNearApproach() {
            return hasNearApproach;
        }

        boolean overlaps(RadarTrack other) {
            if (this.start == null || this.end == null || other.start == null || other.end == null) {
                return false;
            }
            return !(this.end.isBefore(other.start) || other.end.isBefore(this.start));
        }
    }

    private static class RadarContext {
        private final List<RuleHit> scoreHits;
        private final boolean approachDisappear;
        private final int approachTracks;
        private final TimeBand band;

        RadarContext(List<RuleHit> scoreHits,
                     boolean approachDisappear,
                     int approachTracks,
                     TimeBand band) {
            this.scoreHits = scoreHits;
            this.approachDisappear = approachDisappear;
            this.approachTracks = approachTracks;
            this.band = band;
        }

        static RadarContext empty() {
            return new RadarContext(Collections.emptyList(), false, 0, TimeBand.DAY);
        }

        Map<String, Object> toMetadata() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("band", band.name());
            map.put("approachDisappear", approachDisappear);
            map.put("approachTracks", approachTracks);
            List<Map<String, Object>> ruleMeta = new ArrayList<>();
            for (RuleHit hit : scoreHits) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("id", hit.getId());
                item.put("score", hit.getScore());
                item.put("desc", hit.getDescription());
                ruleMeta.add(item);
            }
            map.put("rules", ruleMeta);
            return map;
        }

        List<RuleHit> getScoreHits() {
            return scoreHits;
        }

        boolean isApproachDisappear() {
            return approachDisappear;
        }

        int getApproachTracks() {
            return approachTracks;
        }
    }

    private static class DayCasingState {
        private final boolean markActive;
        private final boolean nightEntryAfterMark;
        private final Instant latestMark;
        private final boolean hasMultiDayCasing;

        DayCasingState(boolean markActive,
                       boolean nightEntryAfterMark,
                       Instant latestMark,
                       boolean hasMultiDayCasing) {
            this.markActive = markActive;
            this.nightEntryAfterMark = nightEntryAfterMark;
            this.latestMark = latestMark;
            this.hasMultiDayCasing = hasMultiDayCasing;
        }

        Map<String, Object> toMetadata() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("markActive", markActive);
            map.put("nightEntryAfterMark", nightEntryAfterMark);
            map.put("latestMark", latestMark);
            map.put("multiDayCasing", hasMultiDayCasing);
            return map;
        }

        boolean isMarkActive() {
            return markActive;
        }

        boolean shouldDirectBlackOnNightReturn() {
            return markActive && nightEntryAfterMark;
        }
    }

    private static class FusionContext {
        private final List<RuleHit> scoreHits;
        private final int arrivals10;
        private final int arrivalsTight;
        private final double arrivalZ;
        private final boolean highDetectability;
        private final List<Instant> history;
        private final boolean nightGroupInAoi;

        FusionContext(List<RuleHit> scoreHits,
                      int arrivals10,
                      int arrivalsTight,
                      double arrivalZ,
                      boolean highDetectability,
                      List<Instant> history,
                      boolean nightGroupInAoi) {
            this.scoreHits = scoreHits;
            this.arrivals10 = arrivals10;
            this.arrivalsTight = arrivalsTight;
            this.arrivalZ = arrivalZ;
            this.highDetectability = highDetectability;
            this.history = history;
            this.nightGroupInAoi = nightGroupInAoi;
        }

        Map<String, Object> toMetadata() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("arrivals10", arrivals10);
            map.put("arrivalsTight", arrivalsTight);
            map.put("arrivalZ", arrivalZ);
            map.put("highDetectability", highDetectability);
            map.put("f3f4History", history.stream().map(Instant::toString).collect(Collectors.toList()));
            map.put("nightGroupInAoi", nightGroupInAoi);
            List<Map<String, Object>> ruleMeta = new ArrayList<>();
            for (RuleHit hit : scoreHits) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("id", hit.getId());
                item.put("score", hit.getScore());
                item.put("desc", hit.getDescription());
                ruleMeta.add(item);
            }
            map.put("rules", ruleMeta);
            return map;
        }

        List<RuleHit> getScoreHits() {
            return scoreHits;
        }

        boolean isNightGroupInAoi() {
            return nightGroupInAoi;
        }
    }
}
