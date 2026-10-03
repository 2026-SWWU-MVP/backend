package com.smwu.backend.material.domain;

public enum MaterialSourceType {
    /** PDF 업로드 → 멀티모달 LLM이 영어 지문만 골라 나눈다 */
    PDF,
    /** 텍스트 붙여넣기 → "---" 줄로 구분된 지문을 코드가 나눈다 (LLM 없음) */
    TEXT
}
