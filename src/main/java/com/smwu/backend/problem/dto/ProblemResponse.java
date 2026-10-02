package com.smwu.backend.problem.dto;

import com.smwu.backend.pastexam.extraction.QuestionType;
import com.smwu.backend.problem.domain.Problem;
import com.smwu.backend.problem.domain.ReviewStatus;
import com.smwu.backend.problem.domain.ValidationStatus;
import com.smwu.backend.problem.type.ProblemAnswer;
import com.smwu.backend.problem.type.ProblemOptions;
import com.smwu.backend.problem.type.ValidationCheck;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 생성 문항 (검수 화면용).
 *
 * @param validationStatus PASSED / NEEDS_REVIEW(블라인드 풀이 불일치 → 강조 표시) / FAILED(규칙 검증 실패)
 * @param validationIssues 통과하지 못한 검증 항목의 이유. PASSED면 빈 목록
 * @param attempts         생성 시도 횟수 (1~3)
 */
public record ProblemResponse(
        Long id,
        Long workspaceId,
        Long profileId,
        Long passageId,
        Long generationJobId,
        QuestionType type,
        String typeLabel,
        ProblemOptions options,
        String passageTitle,
        String stem,
        List<String> conditions,
        String body,
        List<String> choices,
        ProblemAnswer answer,
        String answerText,
        String explanation,
        String evidence,
        ValidationStatus validationStatus,
        List<String> validationIssues,
        int attempts,
        ReviewStatus reviewStatus,
        boolean edited,
        String llmModel,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {

    public static ProblemResponse of(Problem p) {
        List<String> issues = p.getValidationReport() == null ? List.of() : p.getValidationReport().checks().stream()
                .filter(c -> !c.passed())
                .map(ValidationCheck::detail)
                .toList();
        return new ProblemResponse(p.getId(), p.getWorkspaceId(), p.getProfileId(), p.getPassageId(), p.getGenerationJobId(),
                p.getType(), p.getType().getLabel(), p.getOptions(), p.getPassageTitle(), p.getStem(),
                List.copyOf(p.getConditions()), p.getBody(), List.copyOf(p.getChoices()), p.getAnswer(), p.getAnswerText(),
                p.getExplanation(), p.getEvidence(), p.getValidationStatus(), issues,
                p.getValidationReport() == null ? 0 : p.getValidationReport().attempts(),
                p.getReviewStatus(), p.isEdited(), p.getLlmModel(), p.getCreatedAt(), p.getUpdatedAt());
    }
}
