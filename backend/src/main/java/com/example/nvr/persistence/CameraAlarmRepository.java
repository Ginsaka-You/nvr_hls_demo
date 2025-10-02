package com.example.nvr.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

public interface CameraAlarmRepository extends JpaRepository<CameraAlarmEntity, Long> {
}
