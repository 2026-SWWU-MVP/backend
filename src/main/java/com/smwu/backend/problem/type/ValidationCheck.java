package com.smwu.backend.problem.type;

/**
 * 규칙 검증 항목 한 개의 결과.
 *
 * @param name   검증 항목 (예: BLANK_COUNT, EVIDENCE_IN_PASSAGE)
 * @param passed 통과 여부
 * @param detail 실패 이유 (통과면 null). 재생성 프롬프트에 그대로 넣는다
 */
public record ValidationCheck(String name, boolean passed, String detail) {

    public static ValidationCheck pass(String name) {
        return new ValidationCheck(name, true, null);
    }

    public static ValidationCheck fail(String name, String detail) {
        return new ValidationCheck(name, false, detail);
    }

    public static ValidationCheck of(String name, boolean passed, String detailIfFailed) {
        return passed ? pass(name) : fail(name, detailIfFailed);
    }
}
