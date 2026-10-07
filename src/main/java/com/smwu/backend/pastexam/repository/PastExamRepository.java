package com.smwu.backend.pastexam.repository;

import com.smwu.backend.pastexam.domain.PastExam;
import com.smwu.backend.pastexam.domain.PastExamStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface PastExamRepository extends JpaRepository<PastExam, Long> {

    List<PastExam> findByWorkspaceIdOrderByExamYearDescSemesterDescIdDesc(Long workspaceId);

    /** 서버 재시작으로 중단된 추출 작업을 실패 처리한다 */
    @Modifying
    @Query("update PastExam e set e.status = :failed, e.failureReason = :reason where e.status = :extracting")
    int failInterrupted(@Param("extracting") PastExamStatus extracting,
                        @Param("failed") PastExamStatus failed,
                        @Param("reason") String reason);

    boolean existsByWorkspaceId(Long workspaceId);

    long countByWorkspaceId(Long workspaceId);
}
