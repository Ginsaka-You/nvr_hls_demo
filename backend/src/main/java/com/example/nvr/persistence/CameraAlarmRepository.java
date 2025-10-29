package com.example.nvr.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

public interface CameraAlarmRepository extends JpaRepository<CameraAlarmEntity, Long> {

    List<CameraAlarmEntity> findByCreatedAtBetweenOrderByCreatedAtAsc(Instant start, Instant end);

    List<CameraAlarmEntity> findByCamChannelAndCreatedAtBetweenOrderByCreatedAtAsc(String camChannel, Instant start, Instant end);

    long countByCreatedAtBetween(Instant start, Instant end);
}
