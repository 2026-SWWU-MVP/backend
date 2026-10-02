package com.smwu.backend.problem.type;

/**
 * 문제를 만들 원문 지문.
 *
 * @param passageId 시험범위 지문(Passage) ID. TODO(#12): 시험범위 자료 엔티티가 생기면 연결. 그 전에는 null 가능
 * @param title     지문 제목 (없으면 null)
 * @param text      지문 본문 (영문)
 */
public record PassageSource(Long passageId, String title, String text) {
}
