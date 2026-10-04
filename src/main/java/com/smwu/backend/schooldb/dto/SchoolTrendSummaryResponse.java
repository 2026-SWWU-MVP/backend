package com.smwu.backend.schooldb.dto;

import com.smwu.backend.schooldb.domain.SchoolTrendSummary;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 학교 경향 요약 (LLM, 원문 없음).
 *
 * @param headline    한 줄 요약. 학교 DB에 기출이 없으면 null
 * @param cached      이전에 만든 요약을 그대로 돌려줬으면 true (LLM 호출 없음)
 * @param generatedAt 요약을 만든 시각
 */
public record SchoolTrendSummaryResponse(
        Long schoolId,
        int grade,
        int examCount,
        String basis,
        String headline,
        List<String> points,
        List<String> prepTips,
        String llmModel,
        boolean cached,
        LocalDateTime generatedAt
) {

    public static SchoolTrendSummaryResponse of(SchoolTrendSummary s, String basis, boolean cached) {
        return new SchoolTrendSummaryResponse(s.getSchoolId(), s.getGrade(), s.getExamCount(), basis, s.getHeadline(),
                List.copyOf(s.getPoints()), List.copyOf(s.getPrepTips()), s.getLlmModel(), cached, s.getUpdatedAt());
    }

    public static SchoolTrendSummaryResponse empty(Long schoolId, int grade, String basis) {
        return new SchoolTrendSummaryResponse(schoolId, grade, 0, basis, null, List.of(), List.of(), null, false, null);
    }
}
