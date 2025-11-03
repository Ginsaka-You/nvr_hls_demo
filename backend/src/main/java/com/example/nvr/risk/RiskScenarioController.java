package com.example.nvr.risk;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/risk/scenarios")
public class RiskScenarioController {

    private final RiskScenarioService riskScenarioService;

    public RiskScenarioController(RiskScenarioService riskScenarioService) {
        this.riskScenarioService = riskScenarioService;
    }

    @PostMapping("/{scenarioId}")
    public ResponseEntity<RiskScenarioService.ScenarioResult> triggerScenario(@PathVariable String scenarioId) {
        RiskScenarioService.ScenarioResult result = riskScenarioService.runScenario(scenarioId);
        return ResponseEntity.ok(result);
    }

    @DeleteMapping
    public ResponseEntity<Map<String, Object>> resetScenarios() {
        riskScenarioService.cleanupScenarioArtifacts();
        return ResponseEntity.ok(Map.of(
                "ok", true,
                "message", "已清理模拟场景数据"
        ));
    }
}
