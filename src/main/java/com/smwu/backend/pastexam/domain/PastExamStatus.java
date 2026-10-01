package com.smwu.backend.pastexam.domain;

public enum PastExamStatus {
    /** 업로드만 됨 */
    UPLOADED,
    /** 멀티모달 LLM 추출 중 (1~2분) */
    EXTRACTING,
    /** 추출 완료, 검수·수정 가능 */
    EXTRACTED,
    /** 추출 실패. failureReason 확인 후 다시 추출 가능 */
    FAILED
}
