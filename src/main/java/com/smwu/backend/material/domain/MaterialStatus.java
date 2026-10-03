package com.smwu.backend.material.domain;

public enum MaterialStatus {
    /** 업로드만 됨 */
    UPLOADED,
    /** PDF에서 지문을 나누는 중 (LLM, 수십 초~1분) */
    SPLITTING,
    /** 지문 분리 완료. 지문 조회·수정·문제 생성 가능 */
    SPLIT,
    /** 지문 분리 실패. failureReason 확인 후 다시 분리 가능 */
    FAILED
}
