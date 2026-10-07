package com.smwu.backend.problem.dto;

import com.smwu.backend.problem.domain.ReviewStatus;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 문항 수정·채택·폐기 (#16). 보낸 항목만 바뀐다 (null은 그대로).
 *
 * @param answerText   정답지에 찍히는 정답 (정답 구조 answer는 검증용이라 바뀌지 않음)
 * @param reviewStatus ACCEPTED(채택) / REJECTED(폐기) / DRAFT(검수 전으로 되돌리기)
 */
public record UpdateProblemRequest(
        @Size(max = 1000, message = "발문은 1000자 이하입니다.")
        String stem,
        @Size(max = 10, message = "[조건]은 10개까지입니다.")
        List<@Size(max = 300) String> conditions,
        @Size(max = 5000, message = "본문은 5000자 이하입니다.")
        String body,
        @Size(max = 20, message = "[보기]는 20개까지입니다.")
        List<@Size(max = 300) String> choices,
        @Size(max = 2000, message = "정답은 2000자 이하입니다.")
        String answerText,
        @Size(max = 5000, message = "해설은 5000자 이하입니다.")
        String explanation,
        ReviewStatus reviewStatus
) {
}
