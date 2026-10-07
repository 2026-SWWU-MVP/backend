package com.smwu.backend.problem.dto;

import com.smwu.backend.pastexam.extraction.QuestionType;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 워크스페이스 검수 통계 (#42). 생성 작업이 거듭될수록 채택률이 오르는지 보는 용도.
 *
 * @param total  전체
 * @param byType 유형별
 * @param byJob  생성 작업별 (오래된 순) — 채택률 변화
 */
public record ReviewStatsResponse(Counts total, List<TypeCounts> byType, List<JobCounts> byJob) {

    /**
     * @param pending        아직 검수 전(DRAFT)
     * @param edited         강사가 내용을 고친 문항 수
     * @param acceptanceRate 채택 / (채택 + 폐기). 검수한 문항이 없으면 null
     */
    public record Counts(int generated, int accepted, int rejected, int pending, int edited, Double acceptanceRate) {
    }

    public record TypeCounts(QuestionType type, String label, Counts counts) {
    }

    public record JobCounts(Long generationJobId, LocalDateTime createdAt, Counts counts) {
    }
}
