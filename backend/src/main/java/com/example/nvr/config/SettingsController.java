package com.example.nvr.config;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/config")
public class SettingsController {

    private final SettingsService settingsService;

    public SettingsController(SettingsService settingsService) {
        this.settingsService = settingsService;
    }

    @GetMapping
    public SettingsConfig getSettings() {
        return settingsService.getCurrentConfig();
    }

    @PutMapping
    public ResponseEntity<SettingsConfig> updateSettings(@RequestBody SettingsConfig config) {
        SettingsConfig updated = settingsService.update(config);
        return ResponseEntity.ok(updated);
    }
}
