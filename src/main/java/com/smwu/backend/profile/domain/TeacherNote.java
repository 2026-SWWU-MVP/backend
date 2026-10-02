package com.smwu.backend.profile.domain;

/**
 * 강사가 입력한 현장 정보 (예: "이번 서술형은 어구 배열 위주로 낸다고 하심"). #13에서 입력한다.
 *
 * @param persistent 다음 시험 분석에도 계속 적용할지 (기본은 이번 시험만)
 * @param createdBy  작성한 사용자 ID (로그인 전에는 null)
 */
public record TeacherNote(String text, boolean persistent, Long createdBy) {
}
