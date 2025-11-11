package com.example.nvr.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AlertEventRepository extends JpaRepository<AlertEventEntity, Long> {
    boolean existsByEventId(String eventId);

    void deleteByEventIdStartingWith(String prefix);

    long countByEventIdStartingWith(String prefix);
}
