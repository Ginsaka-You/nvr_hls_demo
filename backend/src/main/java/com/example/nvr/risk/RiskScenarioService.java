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
        String message;
        switch (scenarioId) {
            case "light-alert":
                created.put("camera", simulateLightAlert(now));
                message = "已注入轻微警情场景数据";
                break;
            case "repeat-escalation":
                created.put("camera", simulateRepeatedIntrusion(now));
                message = "已注入重复事件升级场景数据";
                break;
            case "challenge-cleared":
                created.put("camera", simulateChallengeCleared(now));
                created.merge("imsi", 2, Integer::sum);
                message = "已注入挑战后离场测试数据";
                break;
            case "challenge-ignored":
                created.put("camera", simulateChallengeIgnored(now));
                created.merge("imsi", 2, Integer::sum);
                message = "已注入挑战无效出警场景数据";
                break;
            case "severe-direct":
                created.put("camera", simulateSevereIntrusion(now));
                message = "已注入严重事件直接警报数据";
                break;
            case "fusion-escalation":
                created.put("camera", simulateFusionEscalation(now));
                created.merge("imsi", 1, Integer::sum);
                created.merge("radar", simulateRadarFusion(now), Integer::sum);
                message = "已注入多源融合升级场景数据";
                break;
            case "imsi-challenge-return":
                created.put("imsi", simulateChallengeReentry(now));
                message = "已注入挑战期内IMSI再识别数据";
                break;
            case "imsi-post-challenge":
                created.put("imsi", simulatePostChallengeReturn(now));
                message = "已注入挑战窗后再现新事件数据";
                break;
            default:
                throw new IllegalArgumentException("未知的场景标识: " + scenarioId);
        }
        riskAssessmentService.recomputeAll();
        return new ScenarioResult(true, scenarioId, created, message);
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

    private int simulateLightAlert(Instant now) {
        Instant eventTime = now.minusSeconds(40);
        createCameraAlarm("light", "outer-01", eventTime, false);
        return 1;
    }

    private int simulateRepeatedIntrusion(Instant now) {
        createCameraAlarm("repeat", "outer-02", now.minusSeconds(120), false);
        createCameraAlarm("repeat", "outer-02", now.minusSeconds(80), false);
        createCameraAlarm("repeat", "outer-02", now.minusSeconds(45), false);
        return 3;
    }

    private int simulateChallengeCleared(Instant now) {
        // IMSI持续但在挑战窗口前结束
        String imsi = "46000" + randomDigits(6);
        createImsiRecord("challenge-cleared", imsi, now.minus(Duration.ofMinutes(16)));
        createImsiRecord("challenge-cleared", imsi, now.minus(Duration.ofMinutes(6)));
        // 摄像头在挑战前记录一次
        createCameraAlarm("challenge-cleared", "outer-03", now.minus(Duration.ofMinutes(6)), false);
        return 1;
    }

    private int simulateChallengeIgnored(Instant now) {
        // IMSI触发F3并由摄像头证明持续存在
        String imsi = "46000" + randomDigits(6);
        createImsiRecord("challenge-ignored", imsi, now.minus(Duration.ofMinutes(16)));
        createImsiRecord("challenge-ignored", imsi, now.minus(Duration.ofMinutes(6)));
        createCameraAlarm("challenge-ignored", "outer-04", now.minus(Duration.ofMinutes(6)), false);
        createCameraAlarm("challenge-ignored", "outer-04", now.minus(Duration.ofMinutes(2)), false);
        return 2;
    }

    private int simulateSevereIntrusion(Instant now) {
        createCameraAlarm("severe", "core-01", now.minusSeconds(20), true);
        return 1;
    }

    private int simulateFusionEscalation(Instant now) {
        createCameraAlarm("fusion", "outer-05", now.minus(Duration.ofMinutes(3)), false);
        createImsiRecord("fusion", "46088" + randomDigits(5), now.minus(Duration.ofMinutes(2)));
        return 1;
    }

    private int simulateRadarFusion(Instant now) {
        RadarTargetEntity entity = new RadarTargetEntity(
                SCENARIO_PREFIX + "fusion",
                6100,
                6200,
                6201,
                true,
                200,
                256,
                1,
                77,
                12.0,
                2.5,
                0.6,
                45.0,
                18.0,
                120,
                35,
                22.5,
                3,
                2,
                1,
                0,
                0,
                0,
                now.minus(Duration.ofMinutes(2))
        );
        radarTargetRepository.save(entity);
        return 1;
    }

    private int simulateChallengeReentry(Instant now) {
        String imsi = "46077" + randomDigits(5);
        createImsiRecord("imsi-challenge", imsi, now.minus(Duration.ofMinutes(18)));
        createImsiRecord("imsi-challenge", imsi, now.minus(Duration.ofMinutes(4)));
        createImsiRecord("imsi-challenge", imsi, now.minus(Duration.ofMinutes(2)));
        return 3;
    }

    private int simulatePostChallengeReturn(Instant now) {
        String imsi = "46055" + randomDigits(5);
        createImsiRecord("imsi-post", imsi, now.minus(Duration.ofMinutes(40)));
        createImsiRecord("imsi-post", imsi, now.minusSeconds(50));
        return 2;
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
