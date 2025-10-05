package com.example.nvr;

import com.example.nvr.persistence.AlertEventEntity;
import com.example.nvr.persistence.AlertEventRepository;
import com.example.nvr.persistence.CameraAlarmEntity;
import com.example.nvr.persistence.CameraAlarmRepository;
import com.example.nvr.persistence.RadarTargetEntity;
import com.example.nvr.persistence.RadarTargetRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/events")
public class EventCenterController {

    private final AlertEventRepository alertEventRepository;
    private final CameraAlarmRepository cameraAlarmRepository;
    private final RadarTargetRepository radarTargetRepository;

    public EventCenterController(AlertEventRepository alertEventRepository,
                                 CameraAlarmRepository cameraAlarmRepository,
                                 RadarTargetRepository radarTargetRepository) {
        this.alertEventRepository = alertEventRepository;
        this.cameraAlarmRepository = cameraAlarmRepository;
        this.radarTargetRepository = radarTargetRepository;
    }

    @GetMapping("/alerts")
    public List<AlertEventEntity> listAlerts(@RequestParam(name = "limit", defaultValue = "100") int limit) {
        return alertEventRepository.findAll(page(limit, Sort.by(Sort.Direction.DESC, "id"))).getContent();
    }

    @GetMapping("/camera-alarms")
    public List<CameraAlarmEntity> listCameraAlarms(@RequestParam(name = "limit", defaultValue = "100") int limit) {
        return cameraAlarmRepository.findAll(page(limit, Sort.by(Sort.Direction.DESC, "createdAt"))).getContent();
    }

    @GetMapping("/radar-targets")
    public List<RadarTargetEntity> listRadarTargets(@RequestParam(name = "limit", defaultValue = "100") int limit) {
        return radarTargetRepository.findAll(page(limit, Sort.by(Sort.Direction.DESC, "capturedAt"))).getContent();
    }

    private PageRequest page(int limit, Sort sort) {
        int size = Math.max(1, Math.min(limit, 500));
        return PageRequest.of(0, size, sort);
    }
}
