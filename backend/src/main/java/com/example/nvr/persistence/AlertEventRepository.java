package com.example.nvr.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AlertEventRepository extends JpaRepository<AlertEventEntity, Long> {
    boolean existsByEventId(String eventId);

    Optional<AlertEventEntity> findByEventId(String eventId);

    void deleteByEventIdStartingWith(String prefix);

    long countByEventIdStartingWith(String prefix);
}
