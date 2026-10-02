package com.smwu.backend.problem.repository;

import com.smwu.backend.problem.domain.Problem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ProblemRepository extends JpaRepository<Problem, Long> {

    List<Problem> findByGenerationJobIdOrderById(Long generationJobId);
}
