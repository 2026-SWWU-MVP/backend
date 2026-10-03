package com.smwu.backend.problem.type;

/**
 * 문제를 만들 원문 지문.
 *
 * @param passageId 시험범위 지문(Passage) ID. 실험·테스트처럼 저장된 지문이 아니면 null
 * @param title     지문 제목 (없으면 null)
 * @param text      지문 본문 (영문)
 */
public record PassageSource(Long passageId, String title, String text) {
}
