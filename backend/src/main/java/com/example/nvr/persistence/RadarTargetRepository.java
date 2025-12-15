package com.example.nvr.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;

import java.time.Instant;
import java.util.List;

public interface RadarTargetRepository extends JpaRepository<RadarTargetEntity, Long> {

    List<RadarTargetEntity> findByCapturedAtBetweenOrderByCapturedAtAsc(Instant start, Instant end);

    List<RadarTargetEntity> findByRadarHostAndTargetIdAndCapturedAtBetweenOrderByCapturedAtAsc(
            String radarHost,
            Integer targetId,
            Instant start,
            Instant end);

    void deleteByRadarHostStartingWith(String prefix);

    long countByRadarHostStartingWith(String prefix);

    Slice<RadarTargetEntity> findAllByOrderByCapturedAtDesc(Pageable pageable);
}
