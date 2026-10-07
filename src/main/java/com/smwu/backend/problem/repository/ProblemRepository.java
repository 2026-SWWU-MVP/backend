package com.smwu.backend.problem.repository;

import com.smwu.backend.problem.domain.Problem;
import com.smwu.backend.problem.domain.ReviewStatus;
import com.smwu.backend.pastexam.extraction.QuestionType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ProblemRepository extends JpaRepository<Problem, Long> {

    List<Problem> findByGenerationJobIdOrderByJobSlotAscIdAsc(Long generationJobId);

    /** 검수·시험지 구성 화면: 워크스페이스의 문항 (검수 상태로 거를 수 있음), 최신순 */
    List<Problem> findByWorkspaceIdOrderByIdDesc(Long workspaceId);

    List<Problem> findByWorkspaceIdAndReviewStatusOrderByIdDesc(Long workspaceId, ReviewStatus reviewStatus);

    /** 같은 생성 작업에서 같은 지문·유형으로 만든 문항 (개별 재생성 때 겹치지 않게) */
    List<Problem> findByGenerationJobIdAndPassageIdAndType(Long generationJobId, Long passageId, QuestionType type);

    /** 생성 작업의 검증 결과 집계: [validationStatus, count] */
    @Query("select p.validationStatus, count(p) from Problem p where p.generationJobId = :jobId group by p.validationStatus")
    List<Object[]> countByValidationStatus(@Param("jobId") Long jobId);
}
