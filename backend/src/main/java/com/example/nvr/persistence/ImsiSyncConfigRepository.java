package com.example.nvr.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ImsiSyncConfigRepository extends JpaRepository<ImsiSyncConfigEntity, Long> {

    Optional<ImsiSyncConfigEntity> findTopByOrderByIdAsc();
}
