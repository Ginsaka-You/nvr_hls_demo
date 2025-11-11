package com.example.nvr.risk;

import com.example.nvr.persistence.AlertEventEntity;
import com.example.nvr.persistence.AlertEventRepository;
import com.example.nvr.persistence.CameraAlarmEntity;
import com.example.nvr.persistence.CameraAlarmRepository;
import com.example.nvr.persistence.ImsiRecordEntity;
import com.example.nvr.persistence.ImsiRecordRepository;
import com.example.nvr.persistence.RadarTargetEntity;
import com.example.nvr.persistence.RadarTargetRepository;
import com.example.nvr.persistence.RiskAssessmentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

@Service
public class RiskScenarioService {

    private static final Logger log = LoggerFactory.getLogger(RiskScenarioService.class);

    private static final String SCENARIO_PREFIX = "SCN-";
    private static final ZoneId DEFAULT_ZONE = ZoneId.systemDefault();
    private static final DateTimeFormatter EVENT_TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(DEFAULT_ZONE);
    private static final DateTimeFormatter REPORT_DATE_FORMAT =
            DateTimeFormatter.ofPattern("yyyyMMdd").withZone(DEFAULT_ZONE);
    private static final DateTimeFormatter REPORT_TIME_FORMAT =
            DateTimeFormatter.ofPattern("HHmmss").withZone(DEFAULT_ZONE);
    private static final DateTimeFormatter RUN_ID_FORMAT =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(DEFAULT_ZONE);
    private static final String SCENARIO_WHITELIST_IMSI = "460000000000001";

    private final AlertEventRepository alertEventRepository;
    private final CameraAlarmRepository cameraAlarmRepository;
    private final ImsiRecordRepository imsiRecordRepository;
    private final RadarTargetRepository radarTargetRepository;
    private final RiskAssessmentRepository riskAssessmentRepository;
    private final RiskAssessmentService riskAssessmentService;
    private final Map<String, ScenarioDefinition> scenarioDefinitions;
    private final ThreadLocal<String> scenarioRunPrefix = new ThreadLocal<>();

    public RiskScenarioService(AlertEventRepository alertEventRepository,
                               CameraAlarmRepository cameraAlarmRepository,
                               ImsiRecordRepository imsiRecordRepository,
                               RadarTargetRepository radarTargetRepository,
                               RiskAssessmentRepository riskAssessmentRepository,
                               RiskAssessmentService riskAssessmentService) {
        this.alertEventRepository = alertEventRepository;
        this.cameraAlarmRepository = cameraAlarmRepository;
        this.imsiRecordRepository = imsiRecordRepository;
        this.radarTargetRepository = radarTargetRepository;
        this.riskAssessmentRepository = riskAssessmentRepository;
        this.riskAssessmentService = riskAssessmentService;
        this.scenarioDefinitions = buildScenarioDefinitions();
    }

    @Transactional
    public ScenarioResult runScenario(String scenarioId) {
        Map<String, Integer> created = new LinkedHashMap<>();
        String normalized = normalizeScenarioId(scenarioId);
        ScenarioDefinition definition = scenarioDefinitions.get(normalized);
        if (definition == null) {
            throw new IllegalArgumentException("未知的场景标识: " + scenarioId);
        }
        String runPrefix = buildScenarioRunPrefix(normalized);
        scenarioRunPrefix.set(runPrefix);
        Instant reference;
        Map<String, Integer> persistedCounts = new LinkedHashMap<>();
        try {
            reference = definition.resolveReference();
            definition.execute(reference, created);
            alertEventRepository.flush();
            cameraAlarmRepository.flush();
            imsiRecordRepository.flush();
            radarTargetRepository.flush();
            long persistedAlerts = alertEventRepository.countByEventIdStartingWith(runPrefix);
            long persistedCamera = cameraAlarmRepository.countByEventIdStartingWith(runPrefix);
            long persistedImsi = imsiRecordRepository.countBySourceFileStartingWith(runPrefix);
            long persistedRadar = radarTargetRepository.countByRadarHostStartingWith(runPrefix);
            if (persistedAlerts > 0) {
                persistedCounts.put("alerts", safeCount(persistedAlerts));
            }
            if (persistedCamera > 0) {
                persistedCounts.put("camera", safeCount(persistedCamera));
            }
            if (persistedImsi > 0) {
                persistedCounts.put("imsi", safeCount(persistedImsi));
            }
            if (persistedRadar > 0) {
                persistedCounts.put("radar", safeCount(persistedRadar));
            }
            if (persistedCounts.isEmpty()) {
                throw new IllegalStateException("虚拟场景未能写入数据库，请检查数据库连接/权限配置");
            }
            log.info("Scenario {} persisted data prefix {} => alerts={}, camera={}, radar={}, imsi={}",
                    normalized, runPrefix, persistedAlerts, persistedCamera, persistedRadar, persistedImsi);
        } finally {
            scenarioRunPrefix.remove();
        }
        Instant effectiveRef = reference != null ? reference : Instant.now();
        riskAssessmentService.recomputeAt(effectiveRef);
        return new ScenarioResult(true, normalized, created, persistedCounts, definition.getMessage(), runPrefix);
    }

    private Map<String, ScenarioDefinition> buildScenarioDefinitions() {
        Map<String, ScenarioDefinition> map = new LinkedHashMap<>();
        map.put("A1", new ScenarioDefinition("A1",
                "昼间单次进入（C4）场景已注入，期望 S_cam=50 → P2 → A2 并开启节流",
                () -> alignToLocalTime(10, 0),
                this::simulateDaySingleEntry));
        map.put("A2", new ScenarioDefinition("A2",
                "昼间 12s 内重复进入（C3）场景已注入，需命中节流窗口",
                () -> alignToLocalTime(10, 5),
                this::simulateDayRepeatEntry));
        map.put("A3", new ScenarioDefinition("A3",
                "昼间徘徊 ≥10s（C5）场景已注入，验证 S_cam=55 → P2 → A2",
                () -> alignToLocalTime(10, 10),
                this::simulateDayLoiterOnly));
        map.put("A4", new ScenarioDefinition("A4",
                "昼间秒退（C6）场景已注入，期望仅下发 A1",
                () -> alignToLocalTime(10, 15),
                this::simulateDayQuickLeave));
        map.put("A5", new ScenarioDefinition("A5",
                "夜间越界 + 雷达协同（C1）场景已注入，验证协同×1.2 与夜乘 1.5",
                () -> alignToLocalTime(22, 0),
                this::simulateNightEntryWithRadar));
        map.put("A6", new ScenarioDefinition("A6",
                "夜间越界 + 徘徊（C2）场景已注入，得分≥90 → P1",
                () -> alignToLocalTime(22, 5),
                this::simulateNightEntryLoiter));
        map.put("A7", new ScenarioDefinition("A7",
                "夜间秒退但雷达强信号（C1 覆盖 C6）场景已注入",
                () -> alignToLocalTime(22, 10),
                this::simulateNightQuickLeaveWithRadar));

        map.put("B1A", new ScenarioDefinition("B1A",
                "夜间 A2 远程警报起始场景已注入，状态应转入 CHALLENGE",
                () -> alignToLocalTime(22, 20),
                this::simulateNightChallengeStart));
        map.put("B1B", new ScenarioDefinition("B1B",
                "夜间等待窗 T−60s 摄像仍异常场景已注入，期望升级 A3",
                () -> alignToLocalTime(22, 25),
                this::simulateNightChallengeCameraPersist));
        map.put("B1C", new ScenarioDefinition("B1C",
                "夜间等待窗 T−30s 雷达仍异常场景已注入，期望升级 A3",
                () -> alignToLocalTime(22, 30),
                this::simulateNightChallengeRadarPersist));
        map.put("B1D", new ScenarioDefinition("B1D",
                "夜间等待窗到期前出现离场场景已注入，应保持在 A2",
                () -> alignToLocalTime(22, 35),
                this::simulateNightChallengeRecovered));

        map.put("C1-X", new ScenarioDefinition("C1-X",
                "昼间双未知 + F2 三强例外闸门场景已注入（81.6 分）",
                () -> alignToLocalTime(11, 0),
                this::simulateDayExceptionUnknownPlusF2));
        map.put("C2-X", new ScenarioDefinition("C2-X",
                "昼间未知 + 重现 + F2 三强例外闸门场景已注入（79.2 分）",
                () -> alignToLocalTime(11, 10),
                this::simulateDayExceptionRepeatPlusF2));
        map.put("C3-X", new ScenarioDefinition("C3-X",
                "昼间 C3-X 标准：双未知 + 重复 + F2 三强（91.2 分）场景已注入",
                () -> alignToLocalTime(11, 20),
                this::simulateDayExceptionLinkPlusF2));
        map.put("C3-X-BORDER", new ScenarioDefinition("C3-X-BORDER",
                "昼间链路 + 单未知边界场景已注入（69.6 分，仅 A1）",
                () -> alignToLocalTime(11, 30),
                this::simulateDayExceptionBorderlineLink));

        map.put("D1", new ScenarioDefinition("D1",
                "昼间同 ROI 600s 内重复触发应节流场景已注入",
                () -> alignToLocalTime(12, 0),
                this::simulateDayThrottleSameRoi));
        map.put("D2", new ScenarioDefinition("D2",
                "昼间不同 ROI 互不节流场景已注入",
                () -> alignToLocalTime(12, 5),
                this::simulateDayThrottleDifferentRoi));
        map.put("D3", new ScenarioDefinition("D3",
                "昼间雷达扇区节流独立场景已注入",
                () -> alignToLocalTime(12, 10),
                this::simulateDayThrottleRadarIndependent));
        map.put("D4", new ScenarioDefinition("D4",
                "夜间远程警报不节流场景已注入",
                () -> alignToLocalTime(22, 45),
                this::simulateNightNoThrottle));

        map.put("W1", new ScenarioDefinition("W1",
                "白名单灰名单候选自动化场景已注入",
                () -> alignToLocalTime(9, 0),
                this::simulateWhitelistCandidate));
        map.put("W2", new ScenarioDefinition("W2",
                "白名单自动加白场景已注入",
                () -> alignToLocalTime(9, 5),
                this::simulateWhitelistPromote));
        map.put("W3", new ScenarioDefinition("W3",
                "白名单自动撤白进入观察场景已注入",
                () -> alignToLocalTime(9, 10),
                this::simulateWhitelistRevoke));
        map.put("W4", new ScenarioDefinition("W4",
                "撤白后再次异常 90 天封禁自动加白场景已注入",
                () -> alignToLocalTime(9, 15),
                this::simulateWhitelistBan));
        map.put("W5", new ScenarioDefinition("W5",
                "白名单当日上限 200 校验场景已注入",
                () -> alignToLocalTime(9, 20),
                this::simulateWhitelistCap));

        map.put("E1", new ScenarioDefinition("E1",
                "多源协同 ×1.2 乘子校验场景已注入",
                () -> alignToLocalTime(22, 55),
                this::simulateCooperationMultiplier));
        map.put("E2", new ScenarioDefinition("E2",
                "白名单 IMSI 不参与协同场景已注入",
                () -> alignToLocalTime(10, 30),
                this::simulateWhitelistNoCooperation));
        map.put("E3", new ScenarioDefinition("E3",
                "白名单撤白后协同立即恢复场景已注入",
                () -> alignToLocalTime(10, 35),
                this::simulateWhitelistImmediateRecovery));

        map.put("F1", new ScenarioDefinition("F1",
                "雷达 <10m 盲区仅记录场景已注入",
                () -> alignToLocalTime(14, 0),
                this::simulateRadarBlindZone));
        map.put("F2", new ScenarioDefinition("F2",
                "雷达 12m 近域持续场景已注入",
                () -> alignToLocalTime(22, 15),
                this::simulateRadarNearZone));

        map.put("G2-1", new ScenarioDefinition("G2-1",
                "夜间等待窗 T 内摄像仍异常 → 应升级 A3 场景已注入",
                () -> alignToLocalTime(23, 5),
                this::simulateG2CameraStillAbnormal));
        map.put("G2-2", new ScenarioDefinition("G2-2",
                "夜间等待窗 T 内仅离场 → 不升级 A3 场景已注入",
                () -> alignToLocalTime(23, 10),
                this::simulateG2CameraCleared));
        map.put("G2-3", new ScenarioDefinition("G2-3",
                "夜间等待窗 T 内雷达仍异常 → 应升级 A3 场景已注入",
                () -> alignToLocalTime(23, 15),
                this::simulateG2RadarStillAbnormal));
        return Collections.unmodifiableMap(map);
    }

    private Instant alignToLocalTime(int hour, int minute) {
        ZonedDateTime base = ZonedDateTime.now(DEFAULT_ZONE).withSecond(0).withNano(0);
        ZonedDateTime target = base.withHour(hour).withMinute(minute);
        if (target.isAfter(base.plusHours(12))) {
            target = target.minusDays(1);
        } else if (target.isBefore(base.minusHours(12))) {
            target = target.plusDays(1);
        }
        return target.toInstant();
    }

    private void simulateDaySingleEntry(Instant reference, Map<String, Integer> created) {
        createCameraEvent("a1-day-single", "cam-roi-a", reference.minusSeconds(5), CameraEventKind.ENTRY, true);
        addCount(created, "camera", 1);
    }

    private void simulateDayRepeatEntry(Instant reference, Map<String, Integer> created) {
        createCameraEvent("a2-day-repeat", "cam-roi-a", reference.minusSeconds(10), CameraEventKind.ENTRY, true);
        createCameraEvent("a2-day-repeat", "cam-roi-a", reference.minusSeconds(2), CameraEventKind.ENTRY, true);
        addCount(created, "camera", 2);
    }

    private void simulateDayLoiterOnly(Instant reference, Map<String, Integer> created) {
        createCameraEvent("a3-day-loiter", "cam-roi-a", reference.minusSeconds(6), CameraEventKind.LOITER, true);
        addCount(created, "camera", 1);
    }

    private void simulateDayQuickLeave(Instant reference, Map<String, Integer> created) {
        createCameraEvent("a4-day-quick", "cam-roi-a", reference.minusSeconds(5), CameraEventKind.ENTRY, true);
        createCameraEvent("a4-day-quick", "cam-roi-a", reference.minusSeconds(3), CameraEventKind.LEAVE, true);
        addCount(created, "camera", 2);
    }

    private void simulateNightEntryWithRadar(Instant reference, Map<String, Integer> created) {
        createCameraEvent("a5-night-radar", "core-night-a5", reference.minusSeconds(10), CameraEventKind.ENTRY, true);
        addCount(created, "camera", 1);
        createRadarTrack("a5-night-radar", 5001, reference.minusSeconds(16), reference.minusSeconds(2),
                18.0, 12.0, 6, created);
    }

    private void simulateNightEntryLoiter(Instant reference, Map<String, Integer> created) {
        createCameraEvent("a6-night-loiter", "core-night-a6", reference.minusSeconds(9), CameraEventKind.ENTRY, true);
        createCameraEvent("a6-night-loiter", "core-night-a6", reference.minusSeconds(2), CameraEventKind.LOITER, true);
        addCount(created, "camera", 2);
    }

    private void simulateNightQuickLeaveWithRadar(Instant reference, Map<String, Integer> created) {
        createCameraEvent("a7-night-quick", "core-night-a7", reference.minusSeconds(6), CameraEventKind.ENTRY, true);
        createCameraEvent("a7-night-quick", "core-night-a7", reference.minusSeconds(4), CameraEventKind.LEAVE, true);
        addCount(created, "camera", 2);
        createRadarTrack("a7-night-quick", 5007, reference.minusSeconds(12), reference.minusSeconds(3),
                17.0, 11.5, 5, created);
    }

    private void simulateNightChallengeStart(Instant reference, Map<String, Integer> created) {
        createCameraEvent("b1a-night-start", "core-night-b1", reference.minusSeconds(8), CameraEventKind.ENTRY, true);
        createCameraEvent("b1a-night-start", "core-night-b1", reference.minusSeconds(3), CameraEventKind.LOITER, true);
        addCount(created, "camera", 2);
    }

    private void simulateNightChallengeCameraPersist(Instant reference, Map<String, Integer> created) {
        Instant challengeStart = reference.minusSeconds(300);
        createCameraEvent("b1b-night-base", "core-night-b1", challengeStart.minusSeconds(6), CameraEventKind.ENTRY, true);
        createCameraEvent("b1b-night-base", "core-night-b1", challengeStart.minusSeconds(2), CameraEventKind.LOITER, true);
        createCameraEvent("b1b-night-still", "core-night-b1", reference.minusSeconds(40), CameraEventKind.ENTRY, true);
        createCameraEvent("b1b-night-still", "core-night-b1", reference.minusSeconds(25), CameraEventKind.LOITER, true);
        addCount(created, "camera", 4);
    }

    private void simulateNightChallengeRadarPersist(Instant reference, Map<String, Integer> created) {
        Instant challengeStart = reference.minusSeconds(300);
        createCameraEvent("b1c-night-base", "core-night-b1", challengeStart.minusSeconds(6), CameraEventKind.ENTRY, true);
        createCameraEvent("b1c-night-base", "core-night-b1", challengeStart.minusSeconds(2), CameraEventKind.LOITER, true);
        addCount(created, "camera", 2);
        createRadarTrack("b1c-night-radar", 5101, reference.minusSeconds(40), reference.minusSeconds(5),
                16.0, 11.0, 6, created);
    }

    private void simulateNightChallengeRecovered(Instant reference, Map<String, Integer> created) {
        Instant challengeStart = reference.minusSeconds(300);
        createCameraEvent("b1d-night-base", "core-night-b1", challengeStart.minusSeconds(6), CameraEventKind.ENTRY, true);
        createCameraEvent("b1d-night-base", "core-night-b1", challengeStart.minusSeconds(2), CameraEventKind.LOITER, true);
        createCameraEvent("b1d-night-leave", "core-night-b1", reference.minusSeconds(15), CameraEventKind.LEAVE, true);
        addCount(created, "camera", 3);
    }

    private void simulateDayExceptionUnknownPlusF2(Instant reference, Map<String, Integer> created) {
        createImsiRecord("c1x-day-f1", "46031" + randomDigits(5), reference.minusSeconds(240));
        createImsiRecord("c1x-day-f1", "46032" + randomDigits(5), reference.minusSeconds(180));
        addCount(created, "imsi", 2);
        createRadarTrack("c1x-day-radar", 5201, reference.minusSeconds(50), reference.minusSeconds(5),
                19.0, 12.0, 7, created);
    }

    private void simulateDayExceptionRepeatPlusF2(Instant reference, Map<String, Integer> created) {
        String repeat = "46033" + randomDigits(5);
        createImsiRecord("c2x-day-repeat", repeat, reference.minusSeconds(1600));
        createImsiRecord("c2x-day-repeat", repeat, reference.minusSeconds(120));
        createImsiRecord("c2x-day-repeat", "46034" + randomDigits(5), reference.minusSeconds(90));
        addCount(created, "imsi", 3);
        createRadarTrack("c2x-day-radar", 5202, reference.minusSeconds(55), reference.minusSeconds(6),
                18.5, 11.5, 7, created);
    }

    private void simulateDayExceptionLinkPlusF2(Instant reference, Map<String, Integer> created) {
        String imsi = "46035" + randomDigits(5);
        createImsiRecord("c3x-day-link", imsi, reference.minusSeconds(230));
        createImsiRecord("c3x-day-link", "46036" + randomDigits(5), reference.minusSeconds(210));
        createImsiRecord("c3x-day-link", imsi, reference.minusSeconds(30 * 60));
        addCount(created, "imsi", 3);
        createRadarTrack("c3x-day-radar", 5203, reference.minusSeconds(45), reference.minusSeconds(4),
                19.5, 11.0, 7, created);
    }

    private void simulateDayExceptionBorderlineLink(Instant reference, Map<String, Integer> created) {
        createImsiRecord("c3x-day-border", "46037" + randomDigits(5), reference.minusSeconds(220));
        addCount(created, "imsi", 1);
        createRadarTrack("c3x-day-border", 5205, reference.minusSeconds(50), reference.minusSeconds(6),
                18.8, 11.2, 6, created);
    }

    private void simulateDayThrottleSameRoi(Instant reference, Map<String, Integer> created) {
        Instant firstCluster = reference.minusSeconds(420);
        createCameraEvent("d1-day-throttle", "cam-roi-throttle", firstCluster.minusSeconds(6), CameraEventKind.ENTRY, true);
        createCameraEvent("d1-day-throttle", "cam-roi-throttle", firstCluster.minusSeconds(2), CameraEventKind.LOITER, true);
        createCameraEvent("d1-day-throttle", "cam-roi-throttle", reference.minusSeconds(8), CameraEventKind.ENTRY, true);
        addCount(created, "camera", 3);
    }

    private void simulateDayThrottleDifferentRoi(Instant reference, Map<String, Integer> created) {
        Instant earlier = reference.minusSeconds(480);
        createCameraEvent("d2-day-roi-a", "cam-roi-a", earlier.minusSeconds(5), CameraEventKind.ENTRY, true);
        createCameraEvent("d2-day-roi-a", "cam-roi-a", earlier.minusSeconds(1), CameraEventKind.LOITER, true);
        createCameraEvent("d2-day-roi-b", "cam-roi-b", reference.minusSeconds(6), CameraEventKind.ENTRY, true);
        addCount(created, "camera", 3);
    }

    private void simulateDayThrottleRadarIndependent(Instant reference, Map<String, Integer> created) {
        Instant earlier = reference.minusSeconds(420);
        createCameraEvent("d3-day-roi", "cam-roi-a", earlier.minusSeconds(5), CameraEventKind.ENTRY, true);
        createCameraEvent("d3-day-roi", "cam-roi-a", earlier.minusSeconds(1), CameraEventKind.LOITER, true);
        addCount(created, "camera", 2);
        createRadarTrack("d3-day-radar", 5204, reference.minusSeconds(55), reference.minusSeconds(5),
                18.0, 11.0, 6, created);
    }

    private void simulateNightNoThrottle(Instant reference, Map<String, Integer> created) {
        createCameraEvent("d4-night-throttle", "core-night-d4", reference.minusSeconds(180), CameraEventKind.ENTRY, true);
        createCameraEvent("d4-night-throttle", "core-night-d4", reference.minusSeconds(60), CameraEventKind.ENTRY, true);
        addCount(created, "camera", 2);
    }

    private void simulateWhitelistCandidate(Instant reference, Map<String, Integer> created) {
        String imsi = "46040" + randomDigits(5);
        createImsiRecord("w1-candidate", imsi, reference.minus(Duration.ofDays(1)).minusSeconds(600));
        createImsiRecord("w1-candidate", imsi, reference.minus(Duration.ofDays(3)).minusSeconds(600));
        createImsiRecord("w1-candidate", imsi, reference.minus(Duration.ofDays(5)).minusSeconds(600));
        createImsiRecord("w1-candidate", imsi, reference.minus(Duration.ofDays(7)).minusSeconds(600));
        createImsiRecord("w1-candidate", imsi, reference.minus(Duration.ofDays(9)).minusSeconds(600));
        addCount(created, "imsi", 5);
    }

    private void simulateWhitelistPromote(Instant reference, Map<String, Integer> created) {
        String imsi = "46041" + randomDigits(5);
        createImsiRecord("w2-promote", imsi, reference.minus(Duration.ofDays(14)).minusSeconds(600));
        addCount(created, "imsi", 1);
    }

    private void simulateWhitelistRevoke(Instant reference, Map<String, Integer> created) {
        String imsi = "46042" + randomDigits(5);
        createImsiRecord("w3-revoke", imsi, reference.minusSeconds(3600));
        createCameraEvent("w3-revoke", "core-night-w", reference.minusSeconds(120), CameraEventKind.ENTRY, true);
        addCount(created, "camera", 1);
        addCount(created, "imsi", 1);
    }

    private void simulateWhitelistBan(Instant reference, Map<String, Integer> created) {
        String imsi = "46043" + randomDigits(5);
        createImsiRecord("w4-ban", imsi, reference.minus(Duration.ofDays(2)).minusSeconds(600));
        createCameraEvent("w4-ban", "core-night-w", reference.minusSeconds(90), CameraEventKind.ENTRY, true);
        addCount(created, "camera", 1);
        addCount(created, "imsi", 1);
    }

    private void simulateWhitelistCap(Instant reference, Map<String, Integer> created) {
        for (int i = 0; i < 210; i++) {
            createImsiRecord("w5-cap", "461" + String.format(Locale.ROOT, "%04d%04d", i / 100, i % 100),
                    reference.minusSeconds(60 + i));
        }
        addCount(created, "imsi", 210);
    }

    private void simulateWhitelistImmediateRecovery(Instant reference, Map<String, Integer> created) {
        Instant base = reference.minusSeconds(240);
        createImsiRecord("e3-whitelist", SCENARIO_WHITELIST_IMSI, base.minusSeconds(60));
        addCount(created, "imsi", 1);
        createRadarTrack("e3-whitelist", 5303, reference.minusSeconds(45), reference.minusSeconds(5),
                18.2, 11.0, 6, created);
        createCameraEvent("e3-whitelist", "core-night-e3", reference.minusSeconds(18), CameraEventKind.ENTRY, true);
        addCount(created, "camera", 1);
        createImsiRecord("e3-whitelist", SCENARIO_WHITELIST_IMSI, reference.minusSeconds(15));
        addCount(created, "imsi", 1);
    }

    private void simulateCooperationMultiplier(Instant reference, Map<String, Integer> created) {
        createCameraEvent("e1-coop", "core-night-e1", reference.minusSeconds(10), CameraEventKind.ENTRY, true);
        addCount(created, "camera", 1);
        createRadarTrack("e1-coop", 5301, reference.minusSeconds(16), reference.minusSeconds(3),
                17.0, 11.0, 6, created);
    }

    private void simulateWhitelistNoCooperation(Instant reference, Map<String, Integer> created) {
        createImsiRecord("e2-no-coop", SCENARIO_WHITELIST_IMSI, reference.minusSeconds(120));
        addCount(created, "imsi", 1);
        createRadarTrack("e2-no-coop", 5302, reference.minusSeconds(40), reference.minusSeconds(5),
                18.0, 18.0, 3, created);
    }

    private void simulateRadarBlindZone(Instant reference, Map<String, Integer> created) {
        createRadarTarget("f1-blind", 9001, 8.0, 8.0, 0.0, 0.0, reference.minusSeconds(30));
        addCount(created, "radar", 1);
    }

    private void simulateRadarNearZone(Instant reference, Map<String, Integer> created) {
        // 近域持续但不逼近：保持距离稳定以避免触发 approach
        createRadarTrack("f2-near", 5401, reference.minusSeconds(20), reference.minusSeconds(2),
                12.5, 12.4, 6, created);
    }

    private void simulateG2CameraStillAbnormal(Instant reference, Map<String, Integer> created) {
        Instant challengeStart = reference.minusSeconds(300);
        createCameraEvent("g2-1-base", "core-night-g2", challengeStart.minusSeconds(6), CameraEventKind.ENTRY, true);
        createCameraEvent("g2-1-base", "core-night-g2", challengeStart.minusSeconds(2), CameraEventKind.LOITER, true);
        createCameraEvent("g2-1-extra", "core-night-g2", reference.minusSeconds(40), CameraEventKind.ENTRY, true);
        createCameraEvent("g2-1-extra", "core-night-g2", reference.minusSeconds(20), CameraEventKind.LOITER, true);
        addCount(created, "camera", 4);
    }

    private void simulateG2CameraCleared(Instant reference, Map<String, Integer> created) {
        Instant challengeStart = reference.minusSeconds(300);
        createCameraEvent("g2-2-base", "core-night-g2", challengeStart.minusSeconds(6), CameraEventKind.ENTRY, true);
        createCameraEvent("g2-2-base", "core-night-g2", challengeStart.minusSeconds(2), CameraEventKind.LOITER, true);
        createCameraEvent("g2-2-leave", "core-night-g2", reference.minusSeconds(30), CameraEventKind.LEAVE, true);
        addCount(created, "camera", 3);
    }

    private void simulateG2RadarStillAbnormal(Instant reference, Map<String, Integer> created) {
        Instant challengeStart = reference.minusSeconds(300);
        createCameraEvent("g2-3-base", "core-night-g2", challengeStart.minusSeconds(6), CameraEventKind.ENTRY, true);
        createCameraEvent("g2-3-base", "core-night-g2", challengeStart.minusSeconds(2), CameraEventKind.LOITER, true);
        addCount(created, "camera", 2);
        createRadarTrack("g2-3-radar", 5402, reference.minusSeconds(28), reference.minusSeconds(3),
                16.0, 10.5, 6, created);
    }

    private void createCameraEvent(String scenario,
                                   String channel,
                                   Instant createdAt,
                                   CameraEventKind kind,
                                   boolean core) {
        String prefix = core ? "core" : "perimeter";
        String eventType = prefix + "-" + kind.getKeyword();
        String level = core ? "critical" : "minor";
        if (kind == CameraEventKind.LEAVE) {
            level = "info";
        }
        String eventId = scenarioArtifactId(scenario);
        String eventTime = EVENT_TIME_FORMAT.format(createdAt);
        CameraAlarmEntity entity = new CameraAlarmEntity(
                eventId,
                eventType,
                channel,
                level,
                eventTime
        );
        entity.setCreatedAt(createdAt);
        cameraAlarmRepository.save(entity);
        createAlertEvent(eventId, eventType, channel, level, eventTime, createdAt);
    }

    private void createAlertEvent(String eventId,
                                  String eventType,
                                  String camChannel,
                                  String level,
                                  String eventTime,
                                  Instant createdAt) {
        AlertEventEntity entity = new AlertEventEntity(
                eventId,
                eventType,
                camChannel,
                level,
                eventTime,
                "未处理"
        );
        entity.setCreatedAt(createdAt);
        alertEventRepository.save(entity);
    }

    private enum CameraEventKind {
        ENTRY("entry"),
        LOITER("loiter"),
        LEAVE("leave");

        private final String keyword;

        CameraEventKind(String keyword) {
            this.keyword = keyword;
        }

        String getKeyword() {
            return keyword;
        }
    }

    private void createRadarTrack(String scenario,
                                  int trackId,
                                  Instant start,
                                  Instant end,
                                  double startRange,
                                  double endRange,
                                  int samples,
                                  Map<String, Integer> created) {
        if (samples < 2) {
            samples = 2;
        }
        if (end.isBefore(start)) {
            Instant tmp = start;
            start = end;
            end = tmp;
            double rangeTmp = startRange;
            startRange = endRange;
            endRange = rangeTmp;
        }
        Duration total = Duration.between(start, end);
        if (total.isZero()) {
            total = Duration.ofSeconds(1);
        }
        Duration step = total.dividedBy(samples - 1);
        double totalSeconds = Math.max(1.0, total.toMillis() / 1000.0);
        double speed = (endRange - startRange) / totalSeconds;
        for (int i = 0; i < samples; i++) {
            Instant timestamp = i == samples - 1 ? end : start.plus(step.multipliedBy(i));
            double fraction = (double) i / (samples - 1);
            double range = startRange + (endRange - startRange) * fraction;
            double longitudinal = range;
            double lateral = 0.2 * i;
            createRadarTarget(scenario, trackId, range, longitudinal, lateral, speed, timestamp);
        }
        addCount(created, "radar", samples);
    }

    private void createImsiRecord(String scenario, String imsi, Instant fetchedAt) {
        String reportDate = REPORT_DATE_FORMAT.format(fetchedAt);
        String reportTime = REPORT_TIME_FORMAT.format(fetchedAt);
        ImsiRecordEntity entity = new ImsiRecordEntity(
                scenario,
                imsi,
                "460",
                "TERRITORY",
                reportDate,
                reportTime,
                scenarioArtifactId(scenario),
                null,
                "scenario-host",
                9000,
                "scenario",
                "scenario injection",
                5L,
                fetchedAt
        );
        imsiRecordRepository.save(entity);
    }

    private void createRadarTarget(String scenario,
                                   int trackId,
                                   double range,
                                   double longitudinal,
                                   double lateral,
                                   double speed,
                                   Instant capturedAt) {
        RadarTargetEntity entity = new RadarTargetEntity(
                scenarioArtifactId(scenario),
                6100,
                6200,
                6201,
                true,
                200,
                256,
                1,
                trackId,
                longitudinal,
                lateral,
                speed,
                range,
                12.0,
                120,
                30,
                15.0,
                3,
                2,
                1,
                0,
                0,
                0,
                capturedAt
        );
        radarTargetRepository.save(entity);
    }

    private void createRadarNoise(String scenario, Instant timestamp) {
        createRadarTarget(scenario, 9000, 250.0, 250.0, 0.0, 0.0, timestamp);
    }

    private String buildScenarioRunPrefix(String normalizedScenarioId) {
        String scenarioPart = (normalizedScenarioId == null || normalizedScenarioId.isBlank())
                ? "SCENARIO"
                : normalizedScenarioId;
        String timestamp = RUN_ID_FORMAT.format(Instant.now());
        String suffix = randomDigits(4);
        return String.format(Locale.ROOT, "%s%s-%s-%s", SCENARIO_PREFIX, scenarioPart, timestamp, suffix);
    }

    private String scenarioArtifactId(String scenario) {
        String prefix = scenarioRunPrefix.get();
        if (prefix == null || prefix.isBlank()) {
            prefix = SCENARIO_PREFIX + "adhoc";
        }
        String suffix = normalizeArtifactSuffix(scenario);
        return prefix + "-" + suffix;
    }

    private String normalizeArtifactSuffix(String raw) {
        if (raw == null || raw.isBlank()) {
            return "event";
        }
        String trimmed = raw.trim();
        StringBuilder builder = new StringBuilder(trimmed.length());
        boolean lastDash = false;
        for (int i = 0; i < trimmed.length(); i++) {
            char ch = trimmed.charAt(i);
            char normalized = Character.toLowerCase(ch);
            if (Character.isLetterOrDigit(ch) || ch == '-' || ch == '_') {
                builder.append(normalized);
                lastDash = false;
                continue;
            }
            if (lastDash) {
                continue;
            }
            builder.append('-');
            lastDash = true;
        }
        if (builder.length() == 0) {
            return "event";
        }
        int end = builder.length() - 1;
        while (end >= 0 && builder.charAt(end) == '-') {
            builder.deleteCharAt(end);
            end--;
        }
        return builder.length() == 0 ? "event" : builder.toString();
    }

    private String normalizeScenarioId(String scenarioId) {
        if (scenarioId == null) {
            return "";
        }
        return scenarioId.trim()
                .toUpperCase()
                .replace('→', '-')
                .replace('＝', '-')
                .replace('=', '-');
    }

    @Transactional
    public void cleanupScenarioArtifacts() {
        cleanupScenarioArtifactsInternal();
    }

    private void cleanupScenarioArtifactsInternal() {
        alertEventRepository.deleteByEventIdStartingWith(SCENARIO_PREFIX);
        cameraAlarmRepository.deleteByEventIdStartingWith(SCENARIO_PREFIX);
        imsiRecordRepository.deleteBySourceFileStartingWith(SCENARIO_PREFIX);
        radarTargetRepository.deleteByRadarHostStartingWith(SCENARIO_PREFIX);
    }

    @Transactional
    public ScenarioResult resetModelState() {
        cleanupScenarioArtifacts();
        long alertCount = alertEventRepository.count();
        long cameraCount = cameraAlarmRepository.count();
        long imsiCount = imsiRecordRepository.count();
        long radarCount = radarTargetRepository.count();
        long assessmentCount = riskAssessmentRepository.count();

        alertEventRepository.deleteAllInBatch();
        cameraAlarmRepository.deleteAllInBatch();
        imsiRecordRepository.deleteAllInBatch();
        radarTargetRepository.deleteAllInBatch();
        riskAssessmentRepository.deleteAllInBatch();

        riskAssessmentService.recomputeAll();

        Map<String, Integer> removed = new LinkedHashMap<>();
        removed.put("alerts", safeCount(alertCount));
        removed.put("camera", safeCount(cameraCount));
        removed.put("imsi", safeCount(imsiCount));
        removed.put("radar", safeCount(radarCount));
        removed.put("assessments", safeCount(assessmentCount));
        String message = "已清空风控模型相关数据，可以重新执行场景测试";
        return new ScenarioResult(true, "reset-model", removed, Map.of(), message, null);
    }

    private void addCount(Map<String, Integer> created, String key, int amount) {
        created.merge(key, amount, Integer::sum);
    }

    private int safeCount(long value) {
        if (value > Integer.MAX_VALUE) {
            return Integer.MAX_VALUE;
        }
        if (value < Integer.MIN_VALUE) {
            return Integer.MIN_VALUE;
        }
        return (int) value;
    }

    private String randomDigits(int length) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        StringBuilder builder = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            builder.append(random.nextInt(10));
        }
        return builder.toString();
    }

    private static class ScenarioDefinition {
        private final String id;
        private final String message;
        private final Supplier<Instant> referenceSupplier;
        private final ScenarioExecutor executor;

        ScenarioDefinition(String id, String message, Supplier<Instant> referenceSupplier, ScenarioExecutor executor) {
            this.id = id;
            this.message = message;
            this.referenceSupplier = referenceSupplier;
            this.executor = executor;
        }

        String getMessage() {
            return message;
        }

        Instant resolveReference() {
            return referenceSupplier != null ? referenceSupplier.get() : Instant.now();
        }

        void execute(Instant reference, Map<String, Integer> created) {
            executor.execute(reference, created);
        }
    }

    @FunctionalInterface
    private interface ScenarioExecutor {
        void execute(Instant reference, Map<String, Integer> created);
    }

    public static class ScenarioResult {
        private final boolean ok;
        private final String scenarioId;
        private final Map<String, Integer> created;
        private final String message;
        private final String artifactPrefix;
        private final Map<String, Integer> persisted;

        public ScenarioResult(boolean ok,
                              String scenarioId,
                              Map<String, Integer> created,
                              Map<String, Integer> persisted,
                              String message,
                              String artifactPrefix) {
            this.ok = ok;
            this.scenarioId = scenarioId;
            this.created = created != null ? Collections.unmodifiableMap(new LinkedHashMap<>(created)) : Map.of();
            this.persisted = persisted != null ? Collections.unmodifiableMap(new LinkedHashMap<>(persisted)) : Map.of();
            this.message = message;
            this.artifactPrefix = artifactPrefix;
        }

        public boolean isOk() {
            return ok;
        }

        public String getScenarioId() {
            return scenarioId;
        }

        public Map<String, Integer> getCreated() {
            return created;
        }

        public String getMessage() {
            return message;
        }

        public String getArtifactPrefix() {
            return artifactPrefix;
        }

        public Map<String, Integer> getPersisted() {
            return persisted;
        }
    }
}
