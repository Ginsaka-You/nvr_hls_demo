package com.example.nvr.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ImsiRecordRepository extends JpaRepository<ImsiRecordEntity, Long> {
}

