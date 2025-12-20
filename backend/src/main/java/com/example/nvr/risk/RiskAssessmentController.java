package com.example.nvr.risk;

import com.example.nvr.persistence.RiskAssessmentEntity;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/risk")
public class RiskAssessmentController {

    private static final Logger log = LoggerFactory.getLogger(RiskAssessmentController.class);

    private final RiskAssessmentService riskAssessmentService;
    private final ObjectMapper objectMapper;

    public RiskAssessmentController(RiskAssessmentService riskAssessmentService,
                                    ObjectMapper objectMapper) {
        this.riskAssessmentService = riskAssessmentService;
        this.objectMapper = objectMapper;
    }

    @GetMapping("/assessments")
    public List<AssessmentResponse> listAssessments(@RequestParam(name = "limit", defaultValue = "100") int limit) {
        return riskAssessmentService.findLatestAssessments(limit).stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    @PostMapping("/assessments/recompute")
    public ResponseEntity<Void> recompute() {
        try {
            riskAssessmentService.recomputeAll();
            return ResponseEntity.accepted().build();
        } catch (Exception ex) {
            log.warn("Failed to recompute risk assessments", ex);
            return ResponseEntity.internalServerError().build();
        }
    }

    @GetMapping("/night-mode")
    public Map<String, Object> nightModeStatus() {
        return toNightModeResponse("当前夜间模式状态");
    }

    @PostMapping("/night-mode/enable")
    public ResponseEntity<Map<String, Object>> enableNightMode() {
        riskAssessmentService.enableNightModeOverride();
        riskAssessmentService.recomputeAt(Instant.now());
        return ResponseEntity.ok(toNightModeResponse("夜间模式演示已开启"));
    }

    @PostMapping("/night-mode/disable")
    public ResponseEntity<Map<String, Object>> disableNightMode() {
        riskAssessmentService.disableNightModeOverride();
        riskAssessmentService.recomputeAt(Instant.now());
        return ResponseEntity.ok(toNightModeResponse("已取消夜间模式演示，恢复昼夜自动判断"));
    }

    private AssessmentResponse toResponse(RiskAssessmentEntity entity) {
        Map<String, Object> details = parseDetails(entity.getDetailsJson());
        return new AssessmentResponse(
                entity.getId(),
                entity.getClassification(),
                entity.getActionType(),
                entity.getScore(),
                entity.getSummary(),
                entity.getWindowStart(),
                entity.getWindowEnd(),
                entity.getUpdatedAt(),
                details
        );
    }

    private Map<String, Object> parseDetails(String json) {
        if (json == null || json.isEmpty()) {
            return Collections.emptyMap();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception ex) {
            log.debug("Failed to parse risk assessment details: {}", json, ex);
            return Collections.emptyMap();
        }
    }

    private Map<String, Object> toNightModeResponse(String message) {
        RiskAssessmentService.NightModeStatus status = riskAssessmentService.getNightModeStatus();
        return Map.of(
                "nightMode", status.isEffectiveNight(),
                "forced", status.isForced(),
                "naturalNight", status.isNaturalNight(),
                "evaluatedAt", status.getEvaluatedAt(),
                "message", message
        );
    }

    public static class AssessmentResponse {
        private final Long id;
        private final String classification;
        private final String actionType;
        private final Integer score;
        private final String summary;
        private final Instant windowStart;
        private final Instant windowEnd;
        private final Instant updatedAt;
        private final Map<String, Object> details;

        public AssessmentResponse(Long id,
                                  String classification,
                                  String actionType,
                                  Integer score,
                                  String summary,
                                  Instant windowStart,
                                  Instant windowEnd,
                                  Instant updatedAt,
                                  Map<String, Object> details) {
            this.id = id;
            this.classification = classification;
            this.actionType = actionType;
            this.score = score;
            this.summary = summary;
            this.windowStart = windowStart;
            this.windowEnd = windowEnd;
            this.updatedAt = updatedAt;
            this.details = details;
        }

        public Long getId() {
            return id;
        }

        public String getClassification() {
            return classification;
        }

        public String getActionType() {
            return actionType;
        }

        public Integer getScore() {
            return score;
        }

        public String getSummary() {
            return summary;
        }

        public Instant getWindowStart() {
            return windowStart;
        }

        public Instant getWindowEnd() {
            return windowEnd;
        }

        public Instant getUpdatedAt() {
            return updatedAt;
        }

        public Map<String, Object> getDetails() {
            return details;
        }
    }
}
