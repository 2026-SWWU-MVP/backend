package com.smwu.backend.profile.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 강사 의견 (현장 정보).
 *
 * @param text       예) 학생들 말로는 이번 서술형은 어구 배열 위주로 낸다고 하심
 * @param persistent 다음 시험 분석에도 계속 적용할지. 기본은 이번 시험만 (false)
 */
public record FeedbackRequest(
        @NotBlank(message = "의견을 입력해 주세요.")
        @Size(max = 1000, message = "의견은 1000자 이하입니다.")
        String text,
        Boolean persistent
) {
}
