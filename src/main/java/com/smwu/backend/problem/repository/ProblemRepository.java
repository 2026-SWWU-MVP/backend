package com.smwu.backend.problem.repository;

import com.smwu.backend.problem.domain.Problem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ProblemRepository extends JpaRepository<Problem, Long> {

    List<Problem> findByGenerationJobIdOrderByJobSlotAscIdAsc(Long generationJobId);

    /** 생성 작업의 검증 결과 집계: [validationStatus, count] */
    @Query("select p.validationStatus, count(p) from Problem p where p.generationJobId = :jobId group by p.validationStatus")
    List<Object[]> countByValidationStatus(@Param("jobId") Long jobId);
}
