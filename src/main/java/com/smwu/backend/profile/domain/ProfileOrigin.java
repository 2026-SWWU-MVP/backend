package com.smwu.backend.profile.domain;

/** 프로필 버전이 만들어진 이유 */
public enum ProfileOrigin {
    /** 기출 분석으로 생성 (#11) */
    INITIAL_ANALYSIS,
    /** AI 재검토 (#13) */
    AI_RECHECK,
    /** 강사 의견 반영 (#13) */
    TEACHER_FEEDBACK,
    /** 강사 직접 수정 (#13) */
    MANUAL_EDIT
}
