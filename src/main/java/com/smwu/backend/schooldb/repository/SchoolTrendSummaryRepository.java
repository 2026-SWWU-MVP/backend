package com.smwu.backend.schooldb.repository;

import com.smwu.backend.schooldb.domain.SchoolTrendSummary;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SchoolTrendSummaryRepository extends JpaRepository<SchoolTrendSummary, Long> {

    Optional<SchoolTrendSummary> findBySchoolIdAndGrade(Long schoolId, int grade);
}
