package com.smwu.backend.schooldb.repository;

import com.smwu.backend.schooldb.domain.ExamContribution;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ExamContributionRepository extends JpaRepository<ExamContribution, Long> {

    Optional<ExamContribution> findByPastExamId(Long pastExamId);

    List<ExamContribution> findBySchoolExamId(Long schoolExamId);
}
