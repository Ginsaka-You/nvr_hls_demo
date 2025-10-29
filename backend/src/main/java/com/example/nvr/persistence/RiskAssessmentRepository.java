package com.example.nvr.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface RiskAssessmentRepository extends JpaRepository<RiskAssessmentEntity, Long> {

    Optional<RiskAssessmentEntity> findFirstBySubjectTypeAndSubjectKey(String subjectType, String subjectKey);

    List<RiskAssessmentEntity> findTop200ByOrderByUpdatedAtDesc();
}
