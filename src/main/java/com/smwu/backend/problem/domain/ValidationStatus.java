package com.smwu.backend.problem.domain;

/** 자동 검증 결과 (검수 상태와 별개) */
public enum ValidationStatus {
    /** 규칙 검증 통과 (블라인드 풀이 검증은 #19) */
    PASSED,
    /** 재생성해도 규칙 검증을 통과하지 못함 */
    FAILED,
    /** 규칙은 통과했지만 블라인드 풀이 결과가 정답과 달라 사람 확인 필요 (#19) */
    NEEDS_REVIEW
}
