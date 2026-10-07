package com.smwu.backend.generation.domain;

import com.smwu.backend.common.domain.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;

/**
 * 문제 생성 작업. 문항마다 병렬로 생성하며, 진행 숫자(completed, errors)는 DB에서 원자적으로 늘린다.
 * 문항별 검증 결과(통과/확인 필요/실패)는 문항(Problem)에서 집계한다.
 */
@Entity
@Table(name = "generation_job", indexes = @Index(name = "idx_generation_job_workspace", columnList = "workspaceId"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class GenerationJob extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long workspaceId;

    /** 사용한 확정 프로필 버전 */
    @Column(nullable = false)
    private Long profileId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private GenerationPlan plan;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private GenerationJobStatus status;

    /** 만들 문항 수 */
    private int total;

    /** 끝난 문항 수 (성공·실패 모두) */
    private int completed;

    /** 오류로 문항을 저장하지 못한 수 (AI 호출 실패 등). 규칙 검증 실패 문항은 저장되므로 여기 들어가지 않는다 */
    private int errors;

    @Column(length = 1000)
    private String failureReason;

    private LocalDateTime finishedAt;

    /** 생성을 요청한 사용자 */
    private Long createdBy;

    public static GenerationJob start(Long workspaceId, Long profileId, GenerationPlan plan, Long createdBy) {
        GenerationJob job = new GenerationJob();
        job.createdBy = createdBy;
        job.workspaceId = workspaceId;
        job.profileId = profileId;
        job.plan = plan;
        job.total = plan.total();
        job.status = GenerationJobStatus.RUNNING;
        return job;
    }

    /** 모든 문항이 끝났으면 상태를 정한다. 여러 스레드가 동시에 불러도 결과가 같다 */
    public void finishIfDone() {
        if (status != GenerationJobStatus.RUNNING || completed < total) {
            return;
        }
        status = errors >= total ? GenerationJobStatus.FAILED : GenerationJobStatus.COMPLETED;
        if (status == GenerationJobStatus.FAILED) {
            failureReason = "모든 문항 생성에 실패했습니다. 잠시 후 다시 시도해 주세요.";
        }
        finishedAt = LocalDateTime.now();
    }

    public int progressPercent() {
        return total == 0 ? 100 : (int) Math.floor(completed * 100.0 / total);
    }
}
