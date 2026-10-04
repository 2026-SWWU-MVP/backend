package com.smwu.backend.generation.repository;

import com.smwu.backend.generation.domain.GenerationJob;
import com.smwu.backend.generation.domain.GenerationJobStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface GenerationJobRepository extends JpaRepository<GenerationJob, Long> {

    List<GenerationJob> findByWorkspaceIdOrderByIdDesc(Long workspaceId);

    /** 병렬로 끝나는 문항들이 진행 숫자를 덮어쓰지 않도록 DB에서 더한다 */
    @Modifying(clearAutomatically = true)
    @Query("update GenerationJob j set j.completed = j.completed + 1, j.errors = j.errors + :error where j.id = :id")
    int increment(@Param("id") Long id, @Param("error") int error);

    /** 서버 재시작으로 중단된 작업을 실패 처리한다 */
    @Modifying
    @Query("update GenerationJob j set j.status = :failed, j.failureReason = :reason where j.status = :running")
    int failInterrupted(@Param("running") GenerationJobStatus running, @Param("failed") GenerationJobStatus failed,
                        @Param("reason") String reason);
}
