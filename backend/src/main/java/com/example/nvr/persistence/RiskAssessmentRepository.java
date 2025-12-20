package com.example.nvr.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface RiskAssessmentRepository extends JpaRepository<RiskAssessmentEntity, Long> {

    Optional<RiskAssessmentEntity> findTopByOrderByUpdatedAtDesc();

    List<RiskAssessmentEntity> findTop200ByOrderByUpdatedAtDesc();

    Slice<RiskAssessmentEntity> findAllByOrderByUpdatedAtDesc(Pageable pageable);

    Slice<RiskAssessmentEntity> findByActionTypeIn(Collection<String> actionTypes, Pageable pageable);
}
