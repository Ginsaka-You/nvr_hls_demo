package com.example.nvr.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AlertEventRepository extends JpaRepository<AlertEventEntity, Long> {
}
