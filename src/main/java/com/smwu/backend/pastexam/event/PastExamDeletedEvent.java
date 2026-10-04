package com.smwu.backend.pastexam.event;

/** 기출이 삭제되었다. 학교별 DB에서 이 기출의 기여를 뺀다 */
public record PastExamDeletedEvent(Long pastExamId) {
}
