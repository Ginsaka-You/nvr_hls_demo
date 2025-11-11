package com.example.nvr.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

public interface ImsiRecordRepository extends JpaRepository<ImsiRecordEntity, Long> {

    List<ImsiRecordEntity> findByImsiAndFetchedAtBetweenOrderByFetchedAtAsc(String imsi, Instant start, Instant end);

    List<ImsiRecordEntity> findByDeviceIdAndFetchedAtBetweenOrderByFetchedAtAsc(String deviceId, Instant start, Instant end);

    List<ImsiRecordEntity> findByImsiAndFetchedAtGreaterThanEqualOrderByFetchedAtAsc(String imsi, Instant start);

    List<ImsiRecordEntity> findByDeviceIdAndFetchedAtGreaterThanEqualOrderByFetchedAtAsc(String deviceId, Instant start);

    List<ImsiRecordEntity> findByFetchedAtBetweenOrderByFetchedAtAsc(Instant start, Instant end);

    void deleteBySourceFileStartingWith(String prefix);

    long countBySourceFileStartingWith(String prefix);
}
