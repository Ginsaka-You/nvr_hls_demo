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
import java.util.*;
import java.util.stream.Collectors;

/**
 * 风控评分与名单判定服务。
 * <p>
 * 该服务会在 IMSI / 摄像头 / 雷达三类事件写入数据库后被调用，
 * 对最近窗口内的数据进行规则匹配、打分，并将结果落入 risk_assessments 表，
 * 供前端风控页面实时展示。
 */
@Service
public class RiskAssessmentService {

    private static final Logger log = LoggerFactory.getLogger(RiskAssessmentService.class);

    private static final Duration SHORT_WINDOW = Duration.ofMinutes(30);
    private static final Duration LONG_WINDOW = Duration.ofMinutes(90);
    private static final Duration SESSION_BREAK = Duration.ofMinutes(15);
    private static final Duration REVISIT_MAX_GAP = Duration.ofHours(2);
    private static final Duration GROUP_TOLERANCE = Duration.ofMinutes(2);
    private static final Duration STAY_GAP_THRESHOLD = Duration.ofMinutes(12);
    private static final Duration DAYTIME_CASING_WINDOW = Duration.ofDays(7);
    private static final Duration FARM_WHITE_WINDOW = Duration.ofDays(14);
    private static final Duration BURST_MERGE = Duration.ofSeconds(90);
    private static final Duration TIME_BUCKET = Duration.ofMinutes(5);
    private static final Duration ARRIVAL_WINDOW = Duration.ofMinutes(10);
    private static final Duration TIGHT_WINDOW = Duration.ofMinutes(5);
    private static final int ARRIVAL_HISTORY_WINDOWS = 6;
    private static final long BUCKET_SECONDS = TIME_BUCKET.getSeconds();

    private static final ZoneId DEFAULT_ZONE = ZoneId.systemDefault();

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

    /**
     * 在新增 IMSI 数据后触发评估。
     */
    @Transactional
    public void processImsiRecordsSaved(List<ImsiRecordEntity> newRecords) {
        if (newRecords == null || newRecords.isEmpty()) {
            return;
        }
        Instant now = Instant.now();
        Instant windowStart = now.minus(LONG_WINDOW);
        List<CameraAlarmEntity> recentCamera = cameraAlarmRepository.findByCreatedAtBetweenOrderByCreatedAtAsc(windowStart, now);
        List<RadarTargetEntity> recentRadar = radarTargetRepository.findByCapturedAtBetweenOrderByCapturedAtAsc(windowStart, now);

        Set<String> imsiKeys = newRecords.stream()
                .map(ImsiRecordEntity::getImsi)
                .filter(RiskAssessmentService::hasText)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        Set<String> deviceKeys = newRecords.stream()
                .filter(it -> isBlank(it.getImsi()))
                .map(ImsiRecordEntity::getDeviceId)
                .filter(RiskAssessmentService::hasText)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        for (String imsi : imsiKeys) {
            evaluateImsiSubject(imsi, null, now, windowStart, recentCamera, recentRadar);
        }
        for (String deviceId : deviceKeys) {
            evaluateImsiSubject(null, deviceId, now, windowStart, recentCamera, recentRadar);
        }
    }

    /**
     * 在新增摄像头告警后触发评估。
     */
    @Transactional
    public void processCameraAlarmSaved(CameraAlarmEntity alarm) {
        if (alarm == null || isBlank(alarm.getCamChannel())) {
            return;
        }
        Instant now = Instant.now();
        Instant windowStart = now.minus(LONG_WINDOW);
        String camChannel = alarm.getCamChannel();
        List<CameraAlarmEntity> channelEvents = cameraAlarmRepository
                .findByCamChannelAndCreatedAtBetweenOrderByCreatedAtAsc(camChannel, windowStart, now);
        List<ImsiRecordEntity> recentImsi = imsiRecordRepository.findByFetchedAtBetweenOrderByFetchedAtAsc(windowStart, now);
        List<RadarTargetEntity> recentRadar = radarTargetRepository.findByCapturedAtBetweenOrderByCapturedAtAsc(windowStart, now);
        evaluateCameraSubject(camChannel, channelEvents, recentImsi, recentRadar, now, windowStart);
    }

    /**
     * 在新增雷达目标后触发评估。
     */
    @Transactional
    public void processRadarTargetsSaved(List<RadarTargetEntity> targets) {
        if (targets == null || targets.isEmpty()) {
            return;
        }
        Instant now = Instant.now();
        Instant windowStart = now.minus(LONG_WINDOW);

        Map<String, List<RadarTargetEntity>> grouped = targets.stream()
                .filter(it -> hasText(it.getRadarHost()) && it.getTargetId() != null)
                .collect(Collectors.groupingBy(it -> radarSubjectKey(it.getRadarHost(), it.getTargetId()), Collectors.toList()));

        List<CameraAlarmEntity> recentCamera = cameraAlarmRepository.findByCreatedAtBetweenOrderByCreatedAtAsc(windowStart, now);
        List<ImsiRecordEntity> recentImsi = imsiRecordRepository.findByFetchedAtBetweenOrderByFetchedAtAsc(windowStart, now);

        for (Map.Entry<String, List<RadarTargetEntity>> entry : grouped.entrySet()) {
            RadarTargetEntity sample = entry.getValue().get(0);
            String radarHost = sample.getRadarHost();
            Integer targetId = sample.getTargetId();
            List<RadarTargetEntity> fullHistory = radarTargetRepository
                    .findByRadarHostAndTargetIdAndCapturedAtBetweenOrderByCapturedAtAsc(
                            radarHost, targetId, windowStart, now);
            evaluateRadarSubject(radarHost, targetId, fullHistory, recentCamera, recentImsi, now, windowStart);
        }
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

    /**
     * 手工触发全量重算（可暴露管理接口调用）。
     */
    @Transactional
    public void recomputeAll() {
        Instant now = Instant.now();
        Instant windowStart = now.minus(LONG_WINDOW);

        List<CameraAlarmEntity> camera = cameraAlarmRepository.findByCreatedAtBetweenOrderByCreatedAtAsc(windowStart, now);
        List<RadarTargetEntity> radar = radarTargetRepository.findByCapturedAtBetweenOrderByCapturedAtAsc(windowStart, now);

        // IMSI
        List<ImsiRecordEntity> imsiWindow = imsiRecordRepository.findByFetchedAtBetweenOrderByFetchedAtAsc(windowStart, now);
        Set<String> imsis = imsiWindow.stream()
                .map(ImsiRecordEntity::getImsi)
                .filter(RiskAssessmentService::hasText)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        for (String imsi : imsis) {
            evaluateImsiSubject(imsi, null, now, windowStart, camera, radar);
        }

        Set<String> deviceOnly = imsiWindow.stream()
                .filter(it -> isBlank(it.getImsi()))
                .map(ImsiRecordEntity::getDeviceId)
                .filter(RiskAssessmentService::hasText)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        for (String device : deviceOnly) {
            evaluateImsiSubject(null, device, now, windowStart, camera, radar);
        }

        Set<String> channels = camera.stream()
                .map(CameraAlarmEntity::getCamChannel)
                .filter(RiskAssessmentService::hasText)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        for (String channel : channels) {
            List<CameraAlarmEntity> channelEvents = camera.stream()
                    .filter(it -> channel.equals(it.getCamChannel()))
                    .collect(Collectors.toList());
            evaluateCameraSubject(channel, channelEvents, imsiWindow, radar, now, windowStart);
        }

        // Radar
        Map<String, List<RadarTargetEntity>> radarGrouped = radar.stream()
                .filter(it -> hasText(it.getRadarHost()) && it.getTargetId() != null)
                .collect(Collectors.groupingBy(it -> radarSubjectKey(it.getRadarHost(), it.getTargetId())));
        for (Map.Entry<String, List<RadarTargetEntity>> entry : radarGrouped.entrySet()) {
            RadarTargetEntity sample = entry.getValue().get(0);
            evaluateRadarSubject(sample.getRadarHost(), sample.getTargetId(), entry.getValue(), camera, imsiWindow, now, windowStart);
        }
    }

    private void evaluateImsiSubject(String imsi,
                                     String deviceId,
                                     Instant now,
                                     Instant windowStart,
                                     List<CameraAlarmEntity> recentCamera,
                                     List<RadarTargetEntity> recentRadar) {
        String subjectType = "IMSI";
        String subjectKey = imsi != null ? imsi : deviceId;
        if (subjectKey == null) {
            return;
        }
        List<ImsiRecordEntity> windowRecords = (imsi != null)
                ? imsiRecordRepository.findByImsiAndFetchedAtBetweenOrderByFetchedAtAsc(imsi, windowStart, now)
                : imsiRecordRepository.findByDeviceIdAndFetchedAtBetweenOrderByFetchedAtAsc(deviceId, windowStart, now);
        windowRecords = filterValidImsi(windowRecords);

        Instant historyStart = now.minus(FARM_WHITE_WINDOW);
        List<ImsiRecordEntity> historyRecords = (imsi != null)
                ? imsiRecordRepository.findByImsiAndFetchedAtGreaterThanEqualOrderByFetchedAtAsc(imsi, historyStart)
                : imsiRecordRepository.findByDeviceIdAndFetchedAtGreaterThanEqualOrderByFetchedAtAsc(deviceId, historyStart);
        historyRecords = filterValidImsi(historyRecords);

        List<ImsiRecordEntity> imsiSafe = recentImsi != null ? recentImsi : Collections.emptyList();
        List<RadarTargetEntity> radarSafe = recentRadar != null ? recentRadar : Collections.emptyList();

        ScoreAccumulator score = new ScoreAccumulator();
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("imsi", imsi);
        metadata.put("deviceId", deviceId);
        metadata.put("recordCountWindow", windowRecords.size());

        if (windowRecords.isEmpty()) {
            persistAssessment(subjectType, subjectKey, now, windowStart, score, metadata,
                    "LOG_ONLY", "最近 90 分钟内无有效 IMSI 记录");
            return;
        }

        windowRecords.sort(Comparator.comparing(ImsiRecordEntity::getFetchedAt));

        // 时间段权重
        Instant shortWindowStart = now.minus(SHORT_WINDOW);
        ImsiPresenceMetrics presence = computeImsiPresenceMetrics(windowRecords, shortWindowStart);
        List<ImsiRecordEntity> burstRecords = presence.getBurstRecords();
        int bucketCount = presence.getBucketCount();
        int burstCountShort = presence.getBurstCount();
        metadata.put("burstCountWindow", burstRecords.size());
        metadata.put("bucketCount30Minutes", bucketCount);
        metadata.put("burstCount30Minutes", burstCountShort);
        metadata.put("bucketStarts30Minutes", presence.getBucketStarts().stream()
                .map(Instant::toString)
                .collect(Collectors.toList()));

        List<Instant> burstInstants = burstRecords.stream()
                .map(ImsiRecordEntity::getFetchedAt)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
        TimeBand dominantBand = determineDominantBand(burstInstants);
        if (dominantBand == TimeBand.NIGHT) {
            score.addScore("T1", 25, "夜间活动");
        } else if (dominantBand == TimeBand.DUSK) {
            score.addScore("T2", 10, "黄昏活动");
        }

        if (bucketCount == 1) {
            score.addScore("B1", 2, "30 分钟窗口命中 1 个时间桶");
        } else if (bucketCount == 2) {
            score.addScore("B2", 8, "30 分钟窗口命中 2 个时间桶");
        } else if (bucketCount == 3) {
            score.addScore("B3", 14, "30 分钟窗口命中 3 个时间桶");
        } else if (bucketCount >= 4) {
            score.addScore("B4", 20, "30 分钟窗口命中 ≥4 个时间桶");
        }

        // 会话划分与结构
        List<ImsiSession> sessions = presence.getSessions();
        metadata.put("sessionCount", sessions.size());

        boolean hasLongGap = sessions.stream().anyMatch(ImsiSession::hasLongGapWithinSession);
        if (hasLongGap) {
            score.addScore("C1", 4, "会话内存在 >12 min 间隔");
        }

        if (sessions.size() >= 2) {
            ImsiSession latest = sessions.get(sessions.size() - 1);
            ImsiSession previous = sessions.get(sessions.size() - 2);
            if (latest.getStart() != null && previous.getEnd() != null) {
                long minutes = Duration.between(previous.getEnd(), latest.getStart()).toMinutes();
                if (minutes > SESSION_BREAK.toMinutes() && minutes <= REVISIT_MAX_GAP.toMinutes()) {
                    score.addScore("C2", 6, "断开 >15 min 后 2 小时内重返");
                }
            }
        }

        // 最新会话停留估计 & 夜间强警戒
        List<Instant> cameraInstants = recentCamera.stream()
                .map(CameraAlarmEntity::getCreatedAt)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
        boolean cameraOverlap = hasSensorMatch(burstInstants, cameraInstants);
        metadata.put("cameraOverlapWithin30Minutes", cameraOverlap);

        if (!sessions.isEmpty()) {
            ImsiSession latest = sessions.get(sessions.size() - 1);
            if (latest.getStart() != null && latest.getEnd() != null) {
                Duration tMin = Duration.between(latest.getStart(), latest.getEnd());
                Duration tHat = Duration.ofMinutes(Math.max(tMin.toMinutes(), (latest.getRecords().size() - 1) * 7L));
                Duration tMax = tMin.plus(Duration.ofMinutes(15));
                metadata.put("latestSession", Map.of(
                        "start", latest.getStart(),
                        "end", latest.getEnd(),
                        "hits", latest.getRecords().size(),
                        "tMinMinutes", tMin.toMinutes(),
                        "tHatMinutes", tHat.toMinutes(),
                        "tMaxMinutes", tMax.toMinutes()
                ));
                TimeBand latestBand = determineDominantBand(Collections.singletonList(latest.getEnd()));
                boolean nightOuterDwell = latestBand == TimeBand.NIGHT && (bucketCount >= 3
                        || tHat.toMinutes() >= 15
                        || tMax.toMinutes() >= 20);
                metadata.put("nightOuterDwellCandidate", nightOuterDwell);
                if (nightOuterDwell && !cameraOverlap) {
                    score.forceStrongAlert("F5", "F5 夜外圈强警（围栏停留异常）");
                }
            }
        }

        // 踩点特征（近 7 天白天 ≥2 天，且夜间命中少）
        Instant casingStart = now.minus(DAYTIME_CASING_WINDOW);
        Map<LocalDate, Long> daytimeCounts = historyRecords.stream()
                .filter(it -> it.getFetchedAt() != null && !it.getFetchedAt().isBefore(casingStart))
                .filter(it -> determineBand(it.getFetchedAt()) != TimeBand.NIGHT)
                .collect(Collectors.groupingBy(it -> LocalDateTime.ofInstant(it.getFetchedAt(), DEFAULT_ZONE).toLocalDate(),
                        Collectors.counting()));
        long daytimeDays = daytimeCounts.entrySet().stream()
                .filter(entry -> entry.getValue() >= 2)
                .count();
        boolean hasNightPresence = historyRecords.stream()
                .anyMatch(it -> it.getFetchedAt() != null && determineBand(it.getFetchedAt()) == TimeBand.NIGHT);
        if (daytimeDays >= 2 && !hasNightPresence) {
            score.addScore("CASE_DAY_PATTERN", 10, "7 天内多日白天外围徘徊（踩点）");
        }

        // 农事白名单模式
        Map<LocalDate, Long> dayOnlyCounts = historyRecords.stream()
                .filter(it -> it.getFetchedAt() != null)
                .filter(it -> determineBand(it.getFetchedAt()) == TimeBand.DAY)
                .collect(Collectors.groupingBy(it -> LocalDateTime.ofInstant(it.getFetchedAt(), DEFAULT_ZONE).toLocalDate(),
                        Collectors.counting()));
        long farmerDays = dayOnlyCounts.entrySet().stream()
                .filter(entry -> entry.getValue() >= 1)
                .count();
        if (farmerDays >= 6 && !hasNightPresence) {
            score.markWhite("W1", "14 天内 ≥6 天白天出现，且无夜间记录");
        }

        // 结伴特征：与摄像头同窗多人
        boolean groupWithCamera = hasCameraGroupMatch(sessions, recentCamera);
        if (groupWithCamera) {
            TimeBand band = determineDominantBand(burstRecords.stream()
                    .map(ImsiRecordEntity::getFetchedAt)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList()));
            if (band == TimeBand.NIGHT) {
                score.addScore("E1", 15, "夜间与摄像头同窗多人出现");
            } else {
                score.addScore("E1_DAY", 8, "白天与摄像头同窗多人出现");
            }
        }

        // 雷达配合：同窗出现
        boolean matchRadar = hasRadarMatch(sessions, recentRadar);
        if (matchRadar) {
            score.addScore("R1", 12, "与雷达同窗出现目标");
        }

        String classification = classify(score);
        String summary = buildSummary(classification, score.getTotalScore(), windowRecords.size(), score.getTopRuleDescription());

        persistAssessment(subjectType, subjectKey, now, windowStart, score, metadata, classification, summary);
    }

    private void evaluateCameraSubject(String camChannel,
                                       List<CameraAlarmEntity> events,
                                       List<ImsiRecordEntity> recentImsi,
                                       List<RadarTargetEntity> recentRadar,
                                       Instant now,
                                       Instant windowStart) {
        if (events == null || events.isEmpty()) {
            return;
        }
        events = events.stream()
                .filter(it -> it.getCreatedAt() != null)
                .sorted(Comparator.comparing(CameraAlarmEntity::getCreatedAt))
                .collect(Collectors.toList());
        if (events.isEmpty()) {
            return;
        }
        ScoreAccumulator score = new ScoreAccumulator();
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("channel", camChannel);
        metadata.put("eventCountWindow", events.size());

        List<Instant> timestamps = events.stream()
                .map(CameraAlarmEntity::getCreatedAt)
                .collect(Collectors.toList());
        CameraAlarmEntity latest = events.get(events.size() - 1);
        Instant anchor = latest.getCreatedAt();
        metadata.put("latestEventTime", anchor);

        TimeBand dominant = determineDominantBand(timestamps);
        if (dominant == TimeBand.NIGHT) {
            score.addScore("T1", 25, "夜间摄像头告警");
        } else if (dominant == TimeBand.DUSK) {
            score.addScore("T2", 10, "黄昏摄像头告警");
        }

        TimeBand latestBand = determineBand(anchor);
        boolean isNight = latestBand == TimeBand.NIGHT;
        if (isNight) {
            score.addScore("D1", 25, "夜间进入 AOI");
        } else {
            score.addScore("D1_DAY", 10, "白天进入 AOI");
        }

        boolean stayDetected = false;
        boolean longStayDetected = false;
        boolean reentryDetected = false;
        for (int i = 1; i < events.size(); i++) {
            Instant prev = events.get(i - 1).getCreatedAt();
            Instant curr = events.get(i).getCreatedAt();
            if (prev == null || curr == null) {
                continue;
            }
            long minutes = Duration.between(prev, curr).toMinutes();
            if (minutes <= 2) {
                stayDetected = true;
            }
            if (minutes <= 3) {
                longStayDetected = true;
            }
            if (minutes <= 10) {
                reentryDetected = true;
            }
        }
        metadata.put("stayDetected", stayDetected);
        metadata.put("reentryDetected10Minutes", reentryDetected);

        if (stayDetected) {
            if (isNight) {
                score.addScore("D2", 15, "夜间 AOI 停留 >60s");
            } else {
                score.addScore("D2_DAY", 8, "白天 AOI 停留 >60s");
            }
        }
        if (longStayDetected && isNight) {
            score.addScore("D3", 10, "夜间 AOI 停留 >180s");
        }
        if (reentryDetected) {
            if (isNight) {
                score.addScore("D4", 12, "夜间 10 分钟内往返 AOI");
                score.markDirectBlack("BLACK_NIGHT_REENTER", "夜间 AOI 往返");
            } else {
                score.addScore("D4_DAY", 6, "白天 10 分钟内往返 AOI");
            }
        }

        Instant thirtyMinutesAgo = now.minus(SHORT_WINDOW);
        long perimeterCount = events.stream()
                .map(CameraAlarmEntity::getCreatedAt)
                .filter(Objects::nonNull)
                .filter(ts -> !ts.isBefore(thirtyMinutesAgo))
                .count();
        boolean cameraPerimeterWander = perimeterCount >= 2 && !stayDetected;
        metadata.put("perimeterCount30Minutes", perimeterCount);
        metadata.put("cameraPerimeterWander", cameraPerimeterWander);
        if (cameraPerimeterWander) {
            if (isNight) {
                score.addScore("D5", 10, "夜间外围徘徊");
            } else {
                score.addScore("D5_DAY", 6, "白天外围徘徊");
            }
        }

        FusionMetrics fusion = computeFusionMetrics(anchor, imsiSafe);
        metadata.put("fusionMetrics", fusion.toMetadata());

        if (fusion.getArrivals10() >= 2 || fusion.getArrivalZ() >= 2.0) {
            score.addScore("F1", isNight ? 15 : 8, "F1 同窗协同（围栏到达异常）");
        }
        if (fusion.getArrivals5() == 0 && fusion.isDetectabilityHigh()) {
            score.addScore("F2", isNight ? 12 : 2, "F2 无手机嫌疑（站点检出率高）");
            if (isNight) {
                score.markForcedGray("GRAY_NIGHT_NO_PHONE", "夜间 AOI 同窗未检出新设备");
            }
        }
        boolean multiPersonHint = hasMultiPersonHint(events);
        metadata.put("multiPersonHint", multiPersonHint);
        boolean f3Triggered = false;
        if (multiPersonHint && fusion.getArrivals5() >= 2) {
            score.addScore("F3", isNight ? 15 : 6, "F3 成伙协同（多人 + 新设备）");
            f3Triggered = true;
            if (isNight) {
                score.markDirectBlack("BLACK_NIGHT_GROUP_IN_AOI", "夜间成伙进入 AOI");
            }
        }
        metadata.put("f3Triggered", f3Triggered);

        if (fusion.getArrivalZ() >= 3.0 && cameraPerimeterWander) {
            score.addScore("F6", isNight ? 10 : 4, "F6 异常汇聚（围栏 z ≥3 + 外围徘徊）");
        }

        boolean casingHistory = false;
        if (isNight) {
            casingHistory = hasRecentDaytimeCasing(camChannel, anchor);
            if (casingHistory) {
                score.markDirectBlack("BLACK_SCOUT_THEN_NIGHT_ENTRY", "F4 踩点→夜返");
            }
        }
        metadata.put("hasDaytimeCasingHistory", casingHistory);

        List<Instant> radarInstants = radarSafe.stream()
                .map(RadarTargetEntity::getCapturedAt)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
        if (hasSensorMatch(timestamps, radarInstants)) {
            score.addScore("R1", 12, "摄像头与雷达同窗检测");
        }

        List<Instant> imsiInstants = imsiSafe.stream()
                .map(ImsiRecordEntity::getFetchedAt)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
        if (fusion.getArrivals10() == 0 && hasSensorMatch(timestamps, imsiInstants)) {
            score.addScore("E_TIMING_MATCH", isNight ? 10 : 6, "摄像头事件与 IMSI 时间窗匹配");
        }

        if (isNight && stayDetected && !reentryDetected) {
            score.markForcedGray("GRAY_NIGHT_AOI_BRIEF", "夜间单人 AOI 停留 >60s");
        }
        if (isNight && cameraPerimeterWander && fusion.getArrivals10() == 0) {
            score.markForcedGray("GRAY_NIGHT_PERIMETER", "夜间外围徘徊且无新设备");
        }

        String classification = classify(score);
        String summary = buildSummary(classification, score.getTotalScore(), events.size(), score.getTopRuleDescription());

        persistAssessment("CAMERA", camChannel, now, windowStart, score, metadata, classification, summary);
    }

    private void evaluateRadarSubject(String radarHost,
                                      Integer targetId,
                                      List<RadarTargetEntity> events,
                                      List<CameraAlarmEntity> recentCamera,
                                      List<ImsiRecordEntity> recentImsi,
                                      Instant now,
                                      Instant windowStart) {
        if (events == null || events.isEmpty()) {
            return;
        }
        events = events.stream()
                .filter(it -> it.getCapturedAt() != null)
                .sorted(Comparator.comparing(RadarTargetEntity::getCapturedAt))
                .collect(Collectors.toList());
        if (events.isEmpty()) {
            return;
        }
        ScoreAccumulator score = new ScoreAccumulator();
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("radarHost", radarHost);
        metadata.put("targetId", targetId);
        metadata.put("eventCountWindow", events.size());

        List<Instant> timestamps = events.stream()
                .map(RadarTargetEntity::getCapturedAt)
                .collect(Collectors.toList());
        TimeBand dominant = determineDominantBand(timestamps);
        if (dominant == TimeBand.NIGHT) {
            score.addScore("T1", 25, "夜间雷达检测目标");
        } else if (dominant == TimeBand.DUSK) {
            score.addScore("T2", 10, "黄昏雷达检测目标");
        }

        // 30 分钟内出现次数
        Instant shortStart = now.minus(SHORT_WINDOW);
        long hitsShort = events.stream()
                .map(RadarTargetEntity::getCapturedAt)
                .filter(ts -> !ts.isBefore(shortStart))
                .count();
        if (hitsShort >= 2) {
            score.addScore("R2", 8, "短时间内雷达多次检测同目标");
        }

        boolean lingering = checkRadarLingering(events);
        if (lingering) {
            if (dominant == TimeBand.NIGHT) {
                score.addScore("R3", 15, "夜间目标徘徊 / 速度缓慢");
            } else {
                score.addScore("R3_DAY", 6, "白天目标徘徊");
            }
        }

        boolean matchCamera = hasSensorMatch(timestamps, recentCamera.stream()
                .map(CameraAlarmEntity::getCreatedAt)
                .filter(Objects::nonNull)
                .collect(Collectors.toList()));
        if (matchCamera) {
            score.addScore("R4", 12, "雷达与摄像头同窗出现");
        }

        boolean matchImsi = hasSensorMatch(timestamps, recentImsi.stream()
                .map(ImsiRecordEntity::getFetchedAt)
                .filter(Objects::nonNull)
                .collect(Collectors.toList()));
        if (matchImsi) {
            score.addScore("R5", 10, "雷达与 IMSI 同窗出现");
        }

        if (dominant == TimeBand.NIGHT && lingering && hitsShort >= 3) {
            score.markDirectBlack("BLACK_RADAR_NIGHT_LINGER", "夜间目标多次徘徊");
        }

        String classification = classify(score);
        String summary = buildSummary(classification, score.getTotalScore(), events.size(), score.getTopRuleDescription());

        persistAssessment("RADAR", radarSubjectKey(radarHost, targetId), now, windowStart, score, metadata, classification, summary);
    }

    private void persistAssessment(String subjectType,
                                   String subjectKey,
                                   Instant now,
                                   Instant windowStart,
                                   ScoreAccumulator score,
                                   Map<String, Object> metadata,
                                   String classification,
                                   String summary) {
        RiskAssessmentEntity entity = riskAssessmentRepository
                .findFirstBySubjectTypeAndSubjectKey(subjectType, subjectKey)
                .orElseGet(() -> new RiskAssessmentEntity(subjectType, subjectKey));
        entity.setSubjectType(subjectType);
        entity.setSubjectKey(subjectKey);
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
        if (!score.getDirectBlackRules().isEmpty() || score.getTotalScore() >= 70) {
            return "BLACK";
        }
        if (score.getTotalScore() >= 55 || score.isForceStrongAlert()) {
            return "STRONG_ALERT";
        }
        if (score.getTotalScore() >= 30 || !score.getForcedGrayRules().isEmpty()) {
            return "GRAY";
        }
        if (!score.getWhiteRules().isEmpty()) {
            return "WHITE";
        }
        return "LOG_ONLY";
    }

    private String buildSummary(String classification, int score, int events, String topRule) {
        String label;
        switch (classification) {
            case "BLACK":
                label = "黑名单";
                break;
            case "STRONG_ALERT":
                label = "强警戒";
                break;
            case "GRAY":
                label = "灰观察";
                break;
            case "WHITE":
                label = "白名单";
                break;
            default:
                label = "记录";
        }
        StringBuilder sb = new StringBuilder(label).append(" | ").append(score).append(" 分");
        sb.append(" | 事件 ").append(events).append(" 条");
        if (hasText(topRule)) {
            sb.append(" | ").append(topRule);
        }
        return sb.toString();
    }

    private String writeDetailsJson(ScoreAccumulator score, Map<String, Object> metadata) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("scoreHits", score.getScoreHits());
        payload.put("forcedGray", score.getForcedGrayRules());
        payload.put("directBlack", score.getDirectBlackRules());
        payload.put("whiteRules", score.getWhiteRules());
        payload.put("strongAlertRules", score.getStrongAlertRules());
        payload.put("metadata", metadata);
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize risk assessment details", e);
            return "{}";
        }
    }

    private ImsiPresenceMetrics computeImsiPresenceMetrics(List<ImsiRecordEntity> records, Instant shortWindowStart) {
        if (records == null || records.isEmpty()) {
            return ImsiPresenceMetrics.empty();
        }
        List<ImsiRecordEntity> sorted = records.stream()
                .filter(Objects::nonNull)
                .filter(it -> it.getFetchedAt() != null)
                .sorted(Comparator.comparing(ImsiRecordEntity::getFetchedAt))
                .collect(Collectors.toList());
        if (sorted.isEmpty()) {
            return ImsiPresenceMetrics.empty();
        }
        List<ImsiRecordEntity> bursts = new ArrayList<>();
        ImsiRecordEntity last = null;
        for (ImsiRecordEntity record : sorted) {
            Instant ts = record.getFetchedAt();
            if (last == null || Duration.between(last.getFetchedAt(), ts).compareTo(BURST_MERGE) > 0) {
                bursts.add(record);
                last = record;
            }
        }
        List<ImsiSession> sessions = buildImsiSessions(bursts);
        List<ImsiRecordEntity> shortBursts = bursts.stream()
                .filter(rec -> !rec.getFetchedAt().isBefore(shortWindowStart))
                .collect(Collectors.toList());
        Set<Instant> bucketStarts = shortBursts.stream()
                .map(rec -> truncateToBucket(rec.getFetchedAt()))
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        return new ImsiPresenceMetrics(bursts, sessions, shortBursts, bucketStarts);
    }

    private Instant truncateToBucket(Instant instant) {
        if (instant == null) {
            return null;
        }
        long epoch = instant.getEpochSecond();
        long bucket = Math.floorDiv(epoch, BUCKET_SECONDS) * BUCKET_SECONDS;
        return Instant.ofEpochSecond(bucket);
    }

    private List<ImsiRecordEntity> filterValidImsi(List<ImsiRecordEntity> records) {
        if (records == null) {
            return Collections.emptyList();
        }
        return records.stream()
                .filter(it -> it != null && it.getFetchedAt() != null)
                .collect(Collectors.toList());
    }

    private static class ImsiPresenceMetrics {
        private final List<ImsiRecordEntity> burstRecords;
        private final List<ImsiSession> sessions;
        private final List<ImsiRecordEntity> shortWindowBursts;
        private final Set<Instant> bucketStarts;

        ImsiPresenceMetrics(List<ImsiRecordEntity> burstRecords,
                            List<ImsiSession> sessions,
                            List<ImsiRecordEntity> shortWindowBursts,
                            Set<Instant> bucketStarts) {
            this.burstRecords = burstRecords;
            this.sessions = sessions;
            this.shortWindowBursts = shortWindowBursts;
            this.bucketStarts = bucketStarts;
        }

        static ImsiPresenceMetrics empty() {
            return new ImsiPresenceMetrics(Collections.emptyList(), Collections.emptyList(), Collections.emptyList(), Collections.emptySet());
        }

        List<ImsiRecordEntity> getBurstRecords() {
            return burstRecords;
        }

        List<ImsiSession> getSessions() {
            return sessions;
        }

        int getBurstCount() {
            return shortWindowBursts.size();
        }

        int getBucketCount() {
            return bucketStarts.size();
        }

        Set<Instant> getBucketStarts() {
            return bucketStarts;
        }
    }

    private List<ImsiSession> buildImsiSessions(List<ImsiRecordEntity> records) {
        List<ImsiSession> sessions = new ArrayList<>();
        ImsiSession current = null;
        ImsiRecordEntity prev = null;
        for (ImsiRecordEntity record : records) {
            if (record == null || record.getFetchedAt() == null) {
                continue;
            }
            if (prev == null) {
                current = new ImsiSession();
                current.add(record);
                sessions.add(current);
                prev = record;
                continue;
            }
            Duration gap = Duration.between(prev.getFetchedAt(), record.getFetchedAt());
            if (gap.compareTo(SESSION_BREAK) > 0) {
                current = new ImsiSession();
                current.add(record);
                sessions.add(current);
            } else {
                current.add(record);
                if (gap.compareTo(STAY_GAP_THRESHOLD) > 0) {
                    current.setHasLongGapWithinSession(true);
                }
            }
            prev = record;
        }
        return sessions;
    }

    private FusionMetrics computeFusionMetrics(Instant anchor, List<ImsiRecordEntity> imsiRecords) {
        if (anchor == null || imsiRecords == null || imsiRecords.isEmpty()) {
            return FusionMetrics.empty();
        }
        Map<String, Instant> firstSeen = new HashMap<>();
        Set<Instant> buckets = new LinkedHashSet<>();
        Instant shortWindowStart = anchor.minus(SHORT_WINDOW);
        for (ImsiRecordEntity record : imsiRecords) {
            Instant ts = record.getFetchedAt();
            if (ts == null || ts.isAfter(anchor)) {
                continue;
            }
            String key = imsiKey(record);
            if (key != null) {
                firstSeen.merge(key, ts, (prev, curr) -> curr.isBefore(prev) ? curr : prev);
            }
            if (!ts.isBefore(shortWindowStart)) {
                Instant bucket = truncateToBucket(ts);
                if (bucket != null) {
                    buckets.add(bucket);
                }
            }
        }
        if (firstSeen.isEmpty()) {
            return new FusionMetrics(0, 0, 0.0, buckets.size(), false, Collections.emptyList());
        }
        int arrivals10 = countFirstSeenBetween(firstSeen, anchor.minus(ARRIVAL_WINDOW), anchor);
        int arrivals5 = countFirstSeenBetween(firstSeen, anchor.minus(TIGHT_WINDOW), anchor);
        List<Integer> history = computeArrivalHistory(firstSeen, anchor);
        double arrivalZ = computeZScore(arrivals10, history);
        double mean = history.isEmpty() ? 0 : history.stream().mapToInt(Integer::intValue).average().orElse(0);
        boolean detectabilityHigh = history.size() >= 3 && mean >= 1.0;
        return new FusionMetrics(arrivals10, arrivals5, arrivalZ, buckets.size(), detectabilityHigh, history);
    }

    private int countFirstSeenBetween(Map<String, Instant> firstSeen, Instant start, Instant end) {
        if (firstSeen.isEmpty() || end == null || start == null) {
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
        for (int i = 0; i < ARRIVAL_HISTORY_WINDOWS; i++) {
            Instant windowStart = windowEnd.minus(ARRIVAL_WINDOW);
            samples.add(countFirstSeenBetween(firstSeen, windowStart, windowEnd));
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
        if (hasText(record.getDeviceId())) {
            return "DEV#" + record.getDeviceId();
        }
        return null;
    }

    private boolean hasMultiPersonHint(List<CameraAlarmEntity> events) {
        if (events == null || events.isEmpty()) {
            return false;
        }
        for (CameraAlarmEntity event : events) {
            String type = event.getEventType();
            if (hasText(type)) {
                String normalized = type.toLowerCase(Locale.ROOT);
                if (normalized.contains("multi") || normalized.contains("多人") || normalized.contains("群体") || normalized.contains("group")) {
                    return true;
                }
            }
            String level = event.getLevel();
            if (hasText(level)) {
                String normalizedLevel = level.toLowerCase(Locale.ROOT);
                if (normalizedLevel.contains("multi") || normalizedLevel.contains("多人") || normalizedLevel.contains("group")) {
                    return true;
                }
            }
        }
        Instant anchor = events.get(events.size() - 1).getCreatedAt();
        if (anchor == null) {
            return false;
        }
        long closeEvents = events.stream()
                .map(CameraAlarmEntity::getCreatedAt)
                .filter(Objects::nonNull)
                .filter(ts -> Math.abs(ts.toEpochMilli() - anchor.toEpochMilli()) <= Duration.ofMinutes(1).toMillis())
                .count();
        return closeEvents >= 2;
    }

    private boolean hasRecentDaytimeCasing(String camChannel, Instant now) {
        if (!hasText(camChannel) || now == null) {
            return false;
        }
        Instant lookbackStart = now.minus(DAYTIME_CASING_WINDOW);
        Instant historyEnd = now.minus(Duration.ofHours(1));
        if (!historyEnd.isAfter(lookbackStart)) {
            historyEnd = now.minus(Duration.ofMinutes(10));
        }
        List<CameraAlarmEntity> history = cameraAlarmRepository
                .findByCamChannelAndCreatedAtBetweenOrderByCreatedAtAsc(camChannel, lookbackStart, historyEnd);
        if (history == null || history.isEmpty()) {
            return false;
        }
        Map<LocalDate, Long> dayCounts = history.stream()
                .map(CameraAlarmEntity::getCreatedAt)
                .filter(Objects::nonNull)
                .filter(ts -> {
                    TimeBand band = determineBand(ts);
                    return band == TimeBand.DAY || band == TimeBand.DUSK;
                })
                .collect(Collectors.groupingBy(ts -> LocalDateTime.ofInstant(ts, DEFAULT_ZONE).toLocalDate(), Collectors.counting()));
        long strongDays = dayCounts.values().stream().filter(count -> count >= 3).count();
        long moderateDays = dayCounts.values().stream().filter(count -> count >= 2).count();
        return strongDays >= 1 || moderateDays >= 2;
    }

    private boolean hasCameraGroupMatch(List<ImsiSession> sessions, List<CameraAlarmEntity> cameraEvents) {
        if (sessions.isEmpty() || cameraEvents == null || cameraEvents.isEmpty()) {
            return false;
        }
        List<Instant> camTimes = cameraEvents.stream()
                .map(CameraAlarmEntity::getCreatedAt)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
        for (ImsiSession session : sessions) {
            for (ImsiRecordEntity record : session.getRecords()) {
                Instant ts = record.getFetchedAt();
                if (ts == null) {
                    continue;
                }
                long matches = camTimes.stream()
                        .filter(cam -> Math.abs(Duration.between(cam, ts).toMinutes()) <= GROUP_TOLERANCE.toMinutes())
                        .count();
                if (matches >= 2) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean hasRadarMatch(List<ImsiSession> sessions, List<RadarTargetEntity> radarEvents) {
        if (sessions.isEmpty() || radarEvents == null || radarEvents.isEmpty()) {
            return false;
        }
        List<Instant> radarTimes = radarEvents.stream()
                .map(RadarTargetEntity::getCapturedAt)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
        for (ImsiSession session : sessions) {
            for (ImsiRecordEntity record : session.getRecords()) {
                Instant ts = record.getFetchedAt();
                if (ts == null) {
                    continue;
                }
                long matches = radarTimes.stream()
                        .filter(radar -> Math.abs(Duration.between(radar, ts).toMinutes()) <= GROUP_TOLERANCE.toMinutes())
                        .count();
                if (matches >= 1) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean hasSensorMatch(List<Instant> sourceInstants, List<Instant> otherInstants) {
        if (sourceInstants == null || sourceInstants.isEmpty() || otherInstants == null || otherInstants.isEmpty()) {
            return false;
        }
        for (Instant ts : sourceInstants) {
            long hits = otherInstants.stream()
                    .filter(it -> Math.abs(Duration.between(it, ts).toMinutes()) <= GROUP_TOLERANCE.toMinutes())
                    .count();
            if (hits >= 1) {
                return true;
            }
        }
        return false;
    }

    private boolean checkRadarLingering(List<RadarTargetEntity> events) {
        for (int i = 1; i < events.size(); i++) {
            RadarTargetEntity prev = events.get(i - 1);
            RadarTargetEntity curr = events.get(i);
            if (prev.getCapturedAt() == null || curr.getCapturedAt() == null) {
                continue;
            }
            Duration gap = Duration.between(prev.getCapturedAt(), curr.getCapturedAt());
            Double speed = curr.getSpeed();
            if (gap.toMinutes() <= 5 && speed != null && Math.abs(speed) < 1.0) {
                return true;
            }
        }
        return false;
    }

    private TimeBand determineBand(Instant instant) {
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

    private TimeBand determineDominantBand(List<Instant> instants) {
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

    private String radarSubjectKey(String radarHost, Integer targetId) {
        return radarHost + "#" + targetId;
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private enum TimeBand {
        DAY,
        DUSK,
        NIGHT
    }

    private static class FusionMetrics {
        private final int arrivals10;
        private final int arrivals5;
        private final double arrivalZ;
        private final int bucketCount;
        private final boolean detectabilityHigh;
        private final List<Integer> historySamples;

        FusionMetrics(int arrivals10,
                      int arrivals5,
                      double arrivalZ,
                      int bucketCount,
                      boolean detectabilityHigh,
                      List<Integer> historySamples) {
            this.arrivals10 = arrivals10;
            this.arrivals5 = arrivals5;
            this.arrivalZ = arrivalZ;
            this.bucketCount = bucketCount;
            this.detectabilityHigh = detectabilityHigh;
            this.historySamples = historySamples;
        }

        static FusionMetrics empty() {
            return new FusionMetrics(0, 0, 0.0, 0, false, Collections.emptyList());
        }

        int getArrivals10() {
            return arrivals10;
        }

        int getArrivals5() {
            return arrivals5;
        }

        double getArrivalZ() {
            return arrivalZ;
        }

        int getBucketCount() {
            return bucketCount;
        }

        boolean isDetectabilityHigh() {
            return detectabilityHigh;
        }

        List<Integer> getHistorySamples() {
            return historySamples;
        }

        Map<String, Object> toMetadata() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("arrivals10Minutes", arrivals10);
            map.put("arrivals5Minutes", arrivals5);
            map.put("arrivalZScore", arrivalZ);
            map.put("bucketCount30Minutes", bucketCount);
            map.put("arrivalHistorySamples", historySamples);
            map.put("detectabilityHigh", detectabilityHigh);
            return map;
        }
    }

    private static class ImsiSession {
        private final List<ImsiRecordEntity> records = new ArrayList<>();
        private boolean hasLongGapWithinSession;

        void add(ImsiRecordEntity record) {
            records.add(record);
        }

        public List<ImsiRecordEntity> getRecords() {
            return records;
        }

        public Instant getStart() {
            return records.isEmpty() ? null : records.get(0).getFetchedAt();
        }

        public Instant getEnd() {
            return records.isEmpty() ? null : records.get(records.size() - 1).getFetchedAt();
        }

        public boolean hasLongGapWithinSession() {
            return hasLongGapWithinSession;
        }

        public void setHasLongGapWithinSession(boolean hasLongGapWithinSession) {
            this.hasLongGapWithinSession = hasLongGapWithinSession;
        }
    }

    private static class ScoreAccumulator {
        private final List<RuleHit> scoreHits = new ArrayList<>();
        private final List<RuleHit> forcedGrayRules = new ArrayList<>();
        private final List<RuleHit> directBlackRules = new ArrayList<>();
        private final List<RuleHit> whiteRules = new ArrayList<>();
        private final List<RuleHit> strongAlertRules = new ArrayList<>();
        private int totalScore = 0;
        private boolean forceStrongAlert = false;

        void addScore(String id, int value, String description) {
            if (value <= 0) {
                return;
            }
            totalScore += value;
            scoreHits.add(new RuleHit(id, value, description));
        }

        void markForcedGray(String id, String description) {
            forcedGrayRules.add(new RuleHit(id, 0, description));
        }

        void markDirectBlack(String id, String description) {
            directBlackRules.add(new RuleHit(id, 0, description));
        }

        void markWhite(String id, String description) {
            whiteRules.add(new RuleHit(id, 0, description));
        }

        void forceStrongAlert(String id, String description) {
            forceStrongAlert = true;
            strongAlertRules.add(new RuleHit(id, 0, description));
        }

        int getTotalScore() {
            return totalScore;
        }

        boolean isForceStrongAlert() {
            return forceStrongAlert;
        }

        List<RuleHit> getScoreHits() {
            return scoreHits;
        }

        List<RuleHit> getForcedGrayRules() {
            return forcedGrayRules;
        }

        List<RuleHit> getDirectBlackRules() {
            return directBlackRules;
        }

        List<RuleHit> getWhiteRules() {
            return whiteRules;
        }

        List<RuleHit> getStrongAlertRules() {
            return strongAlertRules;
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
}
