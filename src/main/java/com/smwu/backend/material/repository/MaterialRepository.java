package com.smwu.backend.material.repository;

import com.smwu.backend.material.domain.Material;
import com.smwu.backend.material.domain.MaterialStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface MaterialRepository extends JpaRepository<Material, Long> {

    List<Material> findByWorkspaceIdOrderByIdDesc(Long workspaceId);

    /** 서버 재시작으로 중단된 지문 분리를 실패 처리한다 */
    @Modifying
    @Query("update Material m set m.status = :failed, m.failureReason = :reason where m.status = :splitting")
    int failInterrupted(@Param("splitting") MaterialStatus splitting, @Param("failed") MaterialStatus failed,
                        @Param("reason") String reason);
}
