package com.example.nvr.risk;

import com.example.nvr.persistence.CameraAlarmEntity;
import com.example.nvr.persistence.CameraAlarmRepository;
import com.example.nvr.persistence.ImsiRecordEntity;
import com.example.nvr.persistence.ImsiRecordRepository;
import com.example.nvr.persistence.RadarTargetEntity;
import com.example.nvr.persistence.RadarTargetRepository;
import com.example.nvr.persistence.RiskAssessmentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

@Service
public class RiskScenarioService {

    private static final String SCENARIO_PREFIX = "SCN-";
    private static final ZoneId DEFAULT_ZONE = ZoneId.systemDefault();
    private static final DateTimeFormatter EVENT_TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(DEFAULT_ZONE);
    private static final DateTimeFormatter REPORT_DATE_FORMAT =
            DateTimeFormatter.ofPattern("yyyyMMdd").withZone(DEFAULT_ZONE);
    private static final DateTimeFormatter REPORT_TIME_FORMAT =
            DateTimeFormatter.ofPattern("HHmmss").withZone(DEFAULT_ZONE);
    private static final String SCENARIO_WHITELIST_IMSI = "460000000000001";

    private final CameraAlarmRepository cameraAlarmRepository;
    private final ImsiRecordRepository imsiRecordRepository;
    private final RadarTargetRepository radarTargetRepository;
    private final RiskAssessmentRepository riskAssessmentRepository;
    private final RiskAssessmentService riskAssessmentService;
    private final Map<String, ScenarioDefinition> scenarioDefinitions;

    public RiskScenarioService(CameraAlarmRepository cameraAlarmRepository,
                               ImsiRecordRepository imsiRecordRepository,
                               RadarTargetRepository radarTargetRepository,
                               RiskAssessmentRepository riskAssessmentRepository,
                               RiskAssessmentService riskAssessmentService) {
        this.cameraAlarmRepository = cameraAlarmRepository;
        this.imsiRecordRepository = imsiRecordRepository;
        this.radarTargetRepository = radarTargetRepository;
        this.riskAssessmentRepository = riskAssessmentRepository;
        this.riskAssessmentService = riskAssessmentService;
        this.scenarioDefinitions = buildScenarioDefinitions();
    }

    @Transactional
    public ScenarioResult runScenario(String scenarioId) {
        cleanupScenarioArtifacts();
        Map<String, Integer> created = new LinkedHashMap<>();
        String normalized = normalizeScenarioId(scenarioId);
        ScenarioDefinition definition = scenarioDefinitions.get(normalized);
        if (definition == null) {
            throw new IllegalArgumentException("未知的场景标识: " + scenarioId);
        }
        Instant reference = definition.resolveReference();
        definition.execute(reference, created);
        riskAssessmentService.recomputeAt(reference);
        return new ScenarioResult(true, normalized, created, definition.getMessage());
    }

    private Map<String, ScenarioDefinition> buildScenarioDefinitions() {
        Map<String, ScenarioDefinition> map = new LinkedHashMap<>();
        map.put("A1", new ScenarioDefinition("A1",
                "夜间核心人形直击场景已注入",
                () -> alignToLocalTime(22, 0),
                this::simulateNightCoreHuman));
        map.put("A2", new ScenarioDefinition("A2",
                "夜间雷达持续逼近场景已注入",
                () -> alignToLocalTime(23, 30),
                this::simulateNightRadarPersistNearApproach));
        map.put("A3", new ScenarioDefinition("A3",
                "夜间外围双未知 IMSI 场景已注入",
                () -> alignToLocalTime(2, 0),
                this::simulateNightTwoUnknownImsi));
        map.put("A4", new ScenarioDefinition("A4",
                "夜间 F1→F2 短暂链路场景已注入",
                () -> alignToLocalTime(1, 10),
                this::simulateNightLinkShortApproach));
        map.put("A5", new ScenarioDefinition("A5",
                "夜间雷达仅持续 11s 场景已注入",
                () -> alignToLocalTime(21, 40),
                this::simulateNightRadarPersistOnly));
        map.put("A6", new ScenarioDefinition("A6",
                "夜间雷达持续且进入近域场景已注入",
                () -> alignToLocalTime(23, 5),
                this::simulateNightRadarPersistNearCore));
        map.put("A7", new ScenarioDefinition("A7",
                "夜间三源协同场景已注入",
                () -> alignToLocalTime(0, 20),
                this::simulateNightMultiSourceFusion));
        map.put("A8", new ScenarioDefinition("A8",
                "夜间雷达短暂单点场景已注入",
                () -> alignToLocalTime(3, 0),
                this::simulateNightRadarShortOnly));
        map.put("A9", new ScenarioDefinition("A9",
                "夜间同一 IMSI 再现场景已注入",
                () -> alignToLocalTime(4, 10),
                this::simulateNightImsiRepeat));
        map.put("A10", new ScenarioDefinition("A10",
                "夜间核心见人且白名单在场场景已注入",
                () -> alignToLocalTime(22, 45),
                this::simulateNightCoreWithWhitelist));
        map.put("B1", new ScenarioDefinition("B1",
                "A2 远程警报等待窗到期核心仍见人场景已注入",
                () -> alignToLocalTime(22, 50),
                this::simulateChallengeCoreStillPresent));
        map.put("B2", new ScenarioDefinition("B2",
                "A2 远程警报等待窗到期雷达仍逼近场景已注入",
                () -> alignToLocalTime(23, 10),
                this::simulateChallengeRadarStillPresent));
        map.put("B3", new ScenarioDefinition("B3",
                "A2 远程警报等待窗未到场景已注入",
                () -> alignToLocalTime(0, 15),
                this::simulateChallengeWindowNotReached));
        map.put("B4", new ScenarioDefinition("B4",
                "A2 远程警报等待窗到期目标离场场景已注入",
                () -> alignToLocalTime(0, 30),
                this::simulateChallengeRecovered));
        map.put("B5", new ScenarioDefinition("B5",
                "白天远程警报等待窗到期仍异常场景已注入",
                () -> alignToLocalTime(11, 0),
                this::simulateDayChallengeStillPresent));
        map.put("C1", new ScenarioDefinition("C1",
                "白天核心越界远程警报场景已注入",
                () -> alignToLocalTime(10, 0),
                this::simulateDayCoreHumanOnly));
        map.put("C2", new ScenarioDefinition("C2",
                "白天高分但仅取证场景已注入",
                () -> alignToLocalTime(15, 30),
                this::simulateDayHighScoreNoAction));
        map.put("C3", new ScenarioDefinition("C3",
                "夜间链路分值不足场景已注入",
                () -> alignToLocalTime(1, 40),
                this::simulateNightLinkLowScore));
        map.put("C4", new ScenarioDefinition("C4",
                "夜间链路持续入闸场景已注入",
                () -> alignToLocalTime(2, 50),
                this::simulateNightLinkPersist));
        map.put("C5", new ScenarioDefinition("C5",
                "白名单 IMSI 抑制场景已注入",
                () -> alignToLocalTime(12, 0),
                this::simulateWhitelistOnly));
        map.put("C6", new ScenarioDefinition("C6",
                "雷达噪声抑制场景已注入",
                () -> alignToLocalTime(13, 0),
                this::simulateRadarNoiseOnly));
        map.put("C7", new ScenarioDefinition("C7",
                "白天核心越界伴随白名单场景已注入",
                () -> alignToLocalTime(11, 20),
                this::simulateDayCoreWithWhitelist));
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

    private void simulateNightCoreHuman(Instant reference, Map<String, Integer> created) {
        createCameraAlarm("a1-night-core", "core-01", reference.minusSeconds(15), true);
        addCount(created, "camera", 1);
    }

    private void simulateNightRadarPersistNearApproach(Instant reference, Map<String, Integer> created) {
        createRadarTrack("a2-night-radar", 2001, reference.minusSeconds(18), reference.minusSeconds(6),
                16.0, 8.0, 4, created);
    }

    private void simulateNightTwoUnknownImsi(Instant reference, Map<String, Integer> created) {
        createImsiRecord("a3-night-imsi", "46011" + randomDigits(5), reference.minusSeconds(180));
        createImsiRecord("a3-night-imsi", "46012" + randomDigits(5), reference.minusSeconds(120));
        addCount(created, "imsi", 2);
    }

    private void simulateNightLinkShortApproach(Instant reference, Map<String, Integer> created) {
        createImsiRecord("a4-night-link", "46013" + randomDigits(5), reference.minusSeconds(180));
        addCount(created, "imsi", 1);
        createRadarTrack("a4-night-link", 2401, reference.minusSeconds(12), reference.minusSeconds(5),
                18.0, 12.0, 3, created);
    }

    private void simulateNightRadarPersistOnly(Instant reference, Map<String, Integer> created) {
        createRadarTrack("a5-night-persist", 2501, reference.minusSeconds(20), reference.minusSeconds(9),
                18.0, 17.0, 4, created);
    }

    private void simulateNightRadarPersistNearCore(Instant reference, Map<String, Integer> created) {
        createRadarTrack("a6-night-near", 2601, reference.minusSeconds(20), reference.minusSeconds(5),
                11.2, 9.8, 5, created);
    }

    private void simulateNightMultiSourceFusion(Instant reference, Map<String, Integer> created) {
        createImsiRecord("a7-night-fusion", "46014" + randomDigits(5), reference.minusSeconds(240));
        createImsiRecord("a7-night-fusion", "46015" + randomDigits(5), reference.minusSeconds(210));
        addCount(created, "imsi", 2);
        createRadarTrack("a7-night-fusion", 2701, reference.minusSeconds(30), reference.minusSeconds(10),
                14.0, 12.0, 5, created);
        createCameraAlarm("a7-night-fusion", "core-02", reference.minusSeconds(15), true);
        addCount(created, "camera", 1);
    }

    private void simulateNightRadarShortOnly(Instant reference, Map<String, Integer> created) {
        createRadarTrack("a8-night-short", 2801, reference.minusSeconds(12), reference.minusSeconds(5),
                16.0, 16.2, 2, created);
    }

    private void simulateNightImsiRepeat(Instant reference, Map<String, Integer> created) {
        String imsi = "46016" + randomDigits(5);
        createImsiRecord("a9-night-repeat", imsi, reference.minusSeconds(20 * 60));
        createImsiRecord("a9-night-repeat", imsi, reference.minusSeconds(90));
        addCount(created, "imsi", 2);
    }

    private void simulateNightCoreWithWhitelist(Instant reference, Map<String, Integer> created) {
        createCameraAlarm("a10-night-whitelist", "core-03", reference.minusSeconds(20), true);
        addCount(created, "camera", 1);
        createImsiRecord("a10-night-whitelist", SCENARIO_WHITELIST_IMSI, reference.minusSeconds(80));
        addCount(created, "imsi", 1);
    }

    private void simulateChallengeCoreStillPresent(Instant reference, Map<String, Integer> created) {
        createCameraAlarm("b1-challenge-core", "core-04", reference.minusSeconds(310), true);
        createCameraAlarm("b1-challenge-core", "core-04", reference.minusSeconds(10), true);
        addCount(created, "camera", 2);
    }

    private void simulateChallengeRadarStillPresent(Instant reference, Map<String, Integer> created) {
        createRadarTrack("b2-challenge-radar", 3201, reference.minusSeconds(310), reference.minusSeconds(5),
                14.0, 8.5, 6, created);
    }

    private void simulateChallengeWindowNotReached(Instant reference, Map<String, Integer> created) {
        createRadarTrack("b3-challenge-soon", 3301, reference.minusSeconds(9), reference.minusSeconds(2),
                11.0, 9.2, 3, created);
    }

    private void simulateChallengeRecovered(Instant reference, Map<String, Integer> created) {
        createRadarTrack("b4-challenge-clear", 3401, reference.minusSeconds(360), reference.minusSeconds(320),
                14.0, 13.6, 3, created);
    }

    private void simulateDayChallengeStillPresent(Instant reference, Map<String, Integer> created) {
        createCameraAlarm("b5-day-challenge", "core-06", reference.minusSeconds(320), true);
        createCameraAlarm("b5-day-challenge", "core-06", reference.minusSeconds(12), true);
        addCount(created, "camera", 2);
    }

    private void simulateDayCoreHumanOnly(Instant reference, Map<String, Integer> created) {
        createCameraAlarm("c1-day-core", "core-05", reference.minusSeconds(20), true);
        addCount(created, "camera", 1);
    }

    private void simulateDayHighScoreNoAction(Instant reference, Map<String, Integer> created) {
        createImsiRecord("c2-day-high", "46017" + randomDigits(5), reference.minusSeconds(260));
        createImsiRecord("c2-day-high", "46018" + randomDigits(5), reference.minusSeconds(210));
        addCount(created, "imsi", 2);
        createRadarTrack("c2-day-high", 3501, reference.minusSeconds(18), reference.minusSeconds(6),
                15.0, 14.4, 4, created);
    }

    private void simulateNightLinkLowScore(Instant reference, Map<String, Integer> created) {
        createImsiRecord("c3-night-low", "46019" + randomDigits(5), reference.minusSeconds(200));
        addCount(created, "imsi", 1);
        createRadarTrack("c3-night-low", 3601, reference.minusSeconds(9), reference.minusSeconds(3),
                15.0, 14.4, 3, created);
    }

    private void simulateNightLinkPersist(Instant reference, Map<String, Integer> created) {
        createImsiRecord("c4-night-link", "46020" + randomDigits(5), reference.minusSeconds(180));
        addCount(created, "imsi", 1);
        createRadarTrack("c4-night-link", 3701, reference.minusSeconds(18), reference.minusSeconds(6),
                14.0, 13.0, 4, created);
    }

    private void simulateWhitelistOnly(Instant reference, Map<String, Integer> created) {
        createImsiRecord("c5-whitelist", SCENARIO_WHITELIST_IMSI, reference.minusSeconds(60));
        addCount(created, "imsi", 1);
    }

    private void simulateRadarNoiseOnly(Instant reference, Map<String, Integer> created) {
        createRadarNoise("c6-noise", reference.minusSeconds(30));
        addCount(created, "radar", 1);
    }

    private void simulateDayCoreWithWhitelist(Instant reference, Map<String, Integer> created) {
        createCameraAlarm("c7-day-whitelist", "core-07", reference.minusSeconds(18), true);
        addCount(created, "camera", 1);
        createImsiRecord("c7-day-whitelist", SCENARIO_WHITELIST_IMSI, reference.minusSeconds(90));
        addCount(created, "imsi", 1);
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

    private void createCameraAlarm(String scenario, String channel, Instant createdAt, boolean core) {
        String id = SCENARIO_PREFIX + scenario + "-" + UUID.randomUUID();
        String type = core ? "core-perimeter-breach" : "perimeter-watch";
        String level = core ? "critical" : "minor";
        CameraAlarmEntity entity = new CameraAlarmEntity(
                id,
                type,
                channel,
                level,
                EVENT_TIME_FORMAT.format(createdAt)
        );
        entity.setCreatedAt(createdAt);
        cameraAlarmRepository.save(entity);
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
                SCENARIO_PREFIX + scenario,
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
                SCENARIO_PREFIX + scenario,
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
        cameraAlarmRepository.deleteByEventIdStartingWith(SCENARIO_PREFIX);
        imsiRecordRepository.deleteBySourceFileStartingWith(SCENARIO_PREFIX);
        radarTargetRepository.deleteByRadarHostStartingWith(SCENARIO_PREFIX);
    }

    @Transactional
    public ScenarioResult resetModelState() {
        cleanupScenarioArtifacts();
        long cameraCount = cameraAlarmRepository.count();
        long imsiCount = imsiRecordRepository.count();
        long radarCount = radarTargetRepository.count();
        long assessmentCount = riskAssessmentRepository.count();

        cameraAlarmRepository.deleteAllInBatch();
        imsiRecordRepository.deleteAllInBatch();
        radarTargetRepository.deleteAllInBatch();
        riskAssessmentRepository.deleteAllInBatch();

        riskAssessmentService.recomputeAll();

        Map<String, Integer> removed = new LinkedHashMap<>();
        removed.put("camera", safeCount(cameraCount));
        removed.put("imsi", safeCount(imsiCount));
        removed.put("radar", safeCount(radarCount));
        removed.put("assessments", safeCount(assessmentCount));
        String message = "已清空风控模型相关数据，可以重新执行场景测试";
        return new ScenarioResult(true, "reset-model", removed, message);
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

        public ScenarioResult(boolean ok, String scenarioId, Map<String, Integer> created, String message) {
            this.ok = ok;
            this.scenarioId = scenarioId;
            this.created = created != null ? Collections.unmodifiableMap(new LinkedHashMap<>(created)) : Map.of();
            this.message = message;
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
    }
}
