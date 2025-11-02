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

    private AssessmentResponse toResponse(RiskAssessmentEntity entity) {
        Map<String, Object> details = parseDetails(entity.getDetailsJson());
        return new AssessmentResponse(
                entity.getId(),
                entity.getClassification(),
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

    public static class AssessmentResponse {
        private final Long id;
        private final String classification;
        private final Integer score;
        private final String summary;
        private final Instant windowStart;
        private final Instant windowEnd;
        private final Instant updatedAt;
        private final Map<String, Object> details;

        public AssessmentResponse(Long id,
                                  String classification,
                                  Integer score,
                                  String summary,
                                  Instant windowStart,
                                  Instant windowEnd,
                                  Instant updatedAt,
                                  Map<String, Object> details) {
            this.id = id;
            this.classification = classification;
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
