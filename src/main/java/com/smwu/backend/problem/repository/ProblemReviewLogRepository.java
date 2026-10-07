package com.smwu.backend.problem.repository;

import com.smwu.backend.problem.domain.ProblemReviewLog;
import com.smwu.backend.problem.domain.ProblemReviewLog.Action;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ProblemReviewLogRepository extends JpaRepository<ProblemReviewLog, Long> {

    /** 프롬프트용: 워크스페이스의 최근 폐기(사유 있음)·수정 기록, 최신순 */
    @Query("select l from ProblemReviewLog l where l.workspaceId = :workspaceId and l.action in :actions order by l.id desc")
    List<ProblemReviewLog> findRecent(@Param("workspaceId") Long workspaceId, @Param("actions") List<Action> actions, Pageable pageable);

    /** 수정한 문항 ID (채택률 통계용) */
    @Query("select distinct l.problemId from ProblemReviewLog l where l.workspaceId = :workspaceId and l.action = :action")
    List<Long> findProblemIds(@Param("workspaceId") Long workspaceId, @Param("action") Action action);
}
