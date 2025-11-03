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
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

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

    private final CameraAlarmRepository cameraAlarmRepository;
    private final ImsiRecordRepository imsiRecordRepository;
    private final RadarTargetRepository radarTargetRepository;
    private final RiskAssessmentRepository riskAssessmentRepository;
    private final RiskAssessmentService riskAssessmentService;

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
    }

    @Transactional
    public ScenarioResult runScenario(String scenarioId) {
        cleanupScenarioArtifacts();
        Instant now = Instant.now();
        Map<String, Integer> created = new LinkedHashMap<>();
        String normalized = normalizeScenarioId(scenarioId);
        String message;
        switch (normalized) {
            case "F1-BASE":
                simulateF1Base(now, created);
                message = "已注入 F1 一般区域闯入的基础验证数据";
                break;
            case "F2-FIRST":
                simulateF2First(now, created);
                message = "已注入 F2 未知 IMSI 首次出现的数据";
                break;
            case "F3-REAPPEAR":
                simulateF3Reappear(now, created);
                message = "已注入 F3 再现/久留并触发 A2 的数据";
                break;
            case "F4-CORELINE":
                simulateF4Coreline(now, created);
                message = "已注入 F4 虚拟警戒线越界直接触发 P1 的数据";
                break;
            case "A1-ONLY":
                simulateA1Only(now, created);
                message = "已注入仅触发 A1 监控记录的低级事件数据";
                break;
            case "A2-SUCCEED":
                simulateA2Succeed(now, created);
                message = "已注入 A2 挑战有效后事件收束的数据";
                break;
            case "A2-FAIL-G2":
                simulateA2FailG2(now, created);
                message = "已注入 A2 挑战无效由 G2 出警的数据";
                break;
            case "G1-P1-A3":
                simulateG1P1A3(now, created);
                message = "已注入 P1 立即触发 G1 出警的数据";
                break;
            case "G2-CHALLENGE":
                simulateG2Challenge(now, created);
                message = "已注入挑战窗口与再识别窗口对齐的验证数据";
                break;
            case "G3-REPEAT":
                simulateG3Repeat(now, created);
                message = "已注入 24 小时内重复触发的巡查派警数据";
                break;
            case "FX-MERGE-UP":
                simulateFusionMergeUp(now, created);
                message = "已注入多源融合上调优先级的数据";
                break;
            case "SM-ONE-A3":
                simulateStateMachineSingleDispatch(now, created);
                message = "已注入一次事件仅出警一次的状态机测试数据";
                break;
            case "SM-CLOSE":
                simulateStateMachineClose(now, created);
                message = "已注入事件在双倍挑战窗后自然收束的数据";
                break;
            case "NEW-INCIDENT":
                simulateNewIncident(now, created);
                message = "已注入挑战窗口后重新出现被视为新事件的数据";
                break;
            case "SYNC-T-REID":
                simulateSyncWindow(now, created);
                message = "已注入挑战窗口与再识别窗口同步边界的数据";
                break;
            case "CD-F1":
                simulateCooldownF1(now, created);
                message = "已注入 F1 30 秒冷却防抖的数据";
                break;
            case "CD-F2":
                simulateCooldownF2(now, created);
                message = "已注入 F2 5 分钟去重冷却的数据";
                break;
            case "CD-F4":
                simulateCooldownF4(now, created);
                message = "已注入 F4 越界 1 分钟冷却防抖的数据";
                break;
            default:
                throw new IllegalArgumentException("未知的场景标识: " + scenarioId);
        }
        riskAssessmentService.recomputeAll();
        return new ScenarioResult(true, normalized, created, message);
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

    private int safeCount(long value) {
        if (value > Integer.MAX_VALUE) {
            return Integer.MAX_VALUE;
        }
        if (value < Integer.MIN_VALUE) {
            return Integer.MIN_VALUE;
        }
        return (int) value;
    }

    private void simulateF1Base(Instant now, Map<String, Integer> created) {
        createCameraAlarm("f1-base", "outer-01", now.minusSeconds(25), false);
        addCount(created, "camera", 1);
    }

    private void simulateF2First(Instant now, Map<String, Integer> created) {
        String imsi = "46010" + randomDigits(5);
        createImsiRecord("f2-first", imsi, now.minus(Duration.ofMinutes(2)));
        addCount(created, "imsi", 1);
    }

    private void simulateF3Reappear(Instant now, Map<String, Integer> created) {
        String imsi = "46020" + randomDigits(5);
        createImsiRecord("f3-reappear", imsi, now.minus(Duration.ofMinutes(26)));
        createImsiRecord("f3-reappear", imsi, now.minus(Duration.ofMinutes(8)));
        createImsiRecord("f3-reappear", imsi, now.minus(Duration.ofMinutes(2)));
        createCameraAlarm("f3-reappear", "outer-02", now.minus(Duration.ofMinutes(2)), false);
        addCount(created, "imsi", 3);
        addCount(created, "camera", 1);
    }

    private void simulateF4Coreline(Instant now, Map<String, Integer> created) {
        createCameraAlarm("f4-core", "core-01", now.minusSeconds(30), true);
        addCount(created, "camera", 1);
    }

    private void simulateA1Only(Instant now, Map<String, Integer> created) {
        createCameraAlarm("a1-only", "outer-03", now.minus(Duration.ofMinutes(4)), false);
        String imsi = "46030" + randomDigits(5);
        createImsiRecord("a1-only", imsi, now.minus(Duration.ofMinutes(5)));
        addCount(created, "camera", 1);
        addCount(created, "imsi", 1);
    }

    private void simulateA2Succeed(Instant now, Map<String, Integer> created) {
        String imsi = "46040" + randomDigits(5);
        createImsiRecord("a2-succeed", imsi, now.minus(Duration.ofMinutes(18)));
        createImsiRecord("a2-succeed", imsi, now.minus(Duration.ofMinutes(6)));
        createCameraAlarm("a2-succeed", "outer-04", now.minus(Duration.ofMinutes(6)), false);
        addCount(created, "imsi", 2);
        addCount(created, "camera", 1);
    }

    private void simulateA2FailG2(Instant now, Map<String, Integer> created) {
        String imsi = "46050" + randomDigits(5);
        createImsiRecord("a2-fail", imsi, now.minus(Duration.ofMinutes(16)));
        createImsiRecord("a2-fail", imsi, now.minus(Duration.ofMinutes(4)));
        createImsiRecord("a2-fail", imsi, now.minus(Duration.ofMinutes(2)));
        createCameraAlarm("a2-fail", "outer-05", now.minus(Duration.ofMinutes(2)), false);
        addCount(created, "imsi", 3);
        addCount(created, "camera", 1);
    }

    private void simulateG1P1A3(Instant now, Map<String, Integer> created) {
        createCameraAlarm("g1-p1", "core-02", now.minusSeconds(40), true);
        createCameraAlarm("g1-p1", "outer-06", now.minusSeconds(55), false);
        addCount(created, "camera", 2);
    }

    private void simulateG2Challenge(Instant now, Map<String, Integer> created) {
        String imsi = "46060" + randomDigits(5);
        createImsiRecord("g2-challenge", imsi, now.minus(Duration.ofMinutes(9)));
        createImsiRecord("g2-challenge", imsi, now.minus(Duration.ofMinutes(4)).minusSeconds(30));
        createCameraAlarm("g2-challenge", "outer-07", now.minus(Duration.ofMinutes(4)).minusSeconds(15), false);

        String imsiNew = "46061" + randomDigits(5);
        createImsiRecord("g2-challenge", imsiNew, now.minus(Duration.ofMinutes(9)));
        createImsiRecord("g2-challenge", imsiNew, now.minusSeconds(30));

        addCount(created, "imsi", 4);
        addCount(created, "camera", 1);
    }

    private void simulateG3Repeat(Instant now, Map<String, Integer> created) {
        createCameraAlarm("g3-repeat", "outer-08", now.minus(Duration.ofHours(20)), false);
        createCameraAlarm("g3-repeat", "outer-08", now.minus(Duration.ofHours(2)), false);
        String imsi = "46070" + randomDigits(5);
        createImsiRecord("g3-repeat", imsi, now.minus(Duration.ofHours(21)));
        createImsiRecord("g3-repeat", imsi, now.minus(Duration.ofHours(1)));
        addCount(created, "camera", 2);
        addCount(created, "imsi", 2);
    }

    private void simulateFusionMergeUp(Instant now, Map<String, Integer> created) {
        createCameraAlarm("fx-merge", "outer-09", now.minus(Duration.ofMinutes(3)), false);
        String imsi = "46080" + randomDigits(5);
        createImsiRecord("fx-merge", imsi, now.minus(Duration.ofMinutes(2)));
        createRadarTarget("fx-merge", now.minus(Duration.ofMinutes(2)));
        addCount(created, "camera", 1);
        addCount(created, "imsi", 1);
        addCount(created, "radar", 1);
    }

    private void simulateStateMachineSingleDispatch(Instant now, Map<String, Integer> created) {
        createCameraAlarm("sm-one", "core-03", now.minusSeconds(90), true);
        createCameraAlarm("sm-one", "core-03", now.minusSeconds(45), true);
        String imsi = "46090" + randomDigits(5);
        createImsiRecord("sm-one", imsi, now.minus(Duration.ofMinutes(4)));
        createImsiRecord("sm-one", imsi, now.minus(Duration.ofMinutes(3)));
        addCount(created, "camera", 2);
        addCount(created, "imsi", 2);
    }

    private void simulateStateMachineClose(Instant now, Map<String, Integer> created) {
        String imsi = "46100" + randomDigits(5);
        createImsiRecord("sm-close", imsi, now.minus(Duration.ofMinutes(18)));
        createImsiRecord("sm-close", imsi, now.minus(Duration.ofMinutes(7)));
        createCameraAlarm("sm-close", "outer-10", now.minus(Duration.ofMinutes(7)), false);
        addCount(created, "imsi", 2);
        addCount(created, "camera", 1);
    }

    private void simulateNewIncident(Instant now, Map<String, Integer> created) {
        String imsi = "46110" + randomDigits(5);
        createImsiRecord("new-incident", imsi, now.minus(Duration.ofMinutes(20)));
        createImsiRecord("new-incident", imsi, now.minus(Duration.ofMinutes(13)));
        createImsiRecord("new-incident", imsi, now.minus(Duration.ofMinutes(3)));
        addCount(created, "imsi", 3);
    }

    private void simulateSyncWindow(Instant now, Map<String, Integer> created) {
        String imsiWithin = "46120" + randomDigits(5);
        createImsiRecord("sync-window", imsiWithin, now.minus(Duration.ofMinutes(6)));
        createImsiRecord("sync-window", imsiWithin, now.minus(Duration.ofMinutes(1)).minusSeconds(10));

        String imsiOutside = "46121" + randomDigits(5);
        createImsiRecord("sync-window", imsiOutside, now.minus(Duration.ofMinutes(6)));
        createImsiRecord("sync-window", imsiOutside, now.minusSeconds(40));

        addCount(created, "imsi", 4);
    }

    private void simulateCooldownF1(Instant now, Map<String, Integer> created) {
        createCameraAlarm("cd-f1", "outer-11", now.minusSeconds(25), false);
        createCameraAlarm("cd-f1", "outer-11", now.minusSeconds(15), false);
        createCameraAlarm("cd-f1", "outer-11", now.minusSeconds(5), false);
        addCount(created, "camera", 3);
    }

    private void simulateCooldownF2(Instant now, Map<String, Integer> created) {
        String imsi = "46130" + randomDigits(5);
        createImsiRecord("cd-f2", imsi, now.minus(Duration.ofMinutes(4)));
        createImsiRecord("cd-f2", imsi, now.minus(Duration.ofMinutes(2)));
        createImsiRecord("cd-f2", imsi, now.minus(Duration.ofMinutes(1)));
        addCount(created, "imsi", 3);
    }

    private void simulateCooldownF4(Instant now, Map<String, Integer> created) {
        createCameraAlarm("cd-f4", "core-04", now.minusSeconds(50), true);
        createCameraAlarm("cd-f4", "core-04", now.minusSeconds(30), true);
        createCameraAlarm("cd-f4", "core-04", now.minusSeconds(10), true);
        addCount(created, "camera", 3);
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

    private void createRadarTarget(String scenario, Instant capturedAt) {
        RadarTargetEntity entity = new RadarTargetEntity(
                SCENARIO_PREFIX + scenario,
                6100,
                6200,
                6201,
                true,
                200,
                256,
                1,
                77,
                11.8,
                2.4,
                2.6,
                0.5,
                44.0,
                118,
                35,
                21.5,
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

    private void addCount(Map<String, Integer> created, String key, int amount) {
        created.merge(key, amount, Integer::sum);
    }

    private String randomDigits(int length) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        StringBuilder builder = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            builder.append(random.nextInt(10));
        }
        return builder.toString();
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
