package com.smwu.backend.generation.dto;

import com.smwu.backend.generation.domain.GenerationJob;
import com.smwu.backend.generation.domain.GenerationJobStatus;
import com.smwu.backend.generation.domain.GenerationPlan;

import java.time.LocalDateTime;

/**
 * 생성 작업 진행 상황. 프론트는 status가 RUNNING인 동안 2초 간격으로 폴링한다.
 *
 * @param completed   끝난 문항 수
 * @param progress    진행률 (0~100)
 * @param passed      검증 통과 문항
 * @param needsReview 블라인드 풀이에서 정답이 애매해 강사 확인이 필요한 문항
 * @param failed      규칙 검증에 끝내 실패했거나 오류로 만들지 못한 문항
 */
public record GenerationJobResponse(
        Long id,
        Long workspaceId,
        Long profileId,
        GenerationJobStatus status,
        GenerationPlan plan,
        int total,
        int completed,
        int progress,
        long passed,
        long needsReview,
        long failed,
        String failureReason,
        LocalDateTime createdAt,
        LocalDateTime finishedAt,
        Long createdBy,
        String createdByName
) {

    public static GenerationJobResponse of(GenerationJob job, long passed, long needsReview, long validationFailed,
                                           String createdByName) {
        return new GenerationJobResponse(job.getId(), job.getWorkspaceId(), job.getProfileId(), job.getStatus(), job.getPlan(),
                job.getTotal(), job.getCompleted(), job.progressPercent(), passed, needsReview,
                validationFailed + job.getErrors(), job.getFailureReason(), job.getCreatedAt(), job.getFinishedAt(),
                job.getCreatedBy(), createdByName);
    }
}
