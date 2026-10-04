package com.smwu.backend.pastexam.event;

/**
 * 기출의 추출 결과가 새로 저장되었거나 강사가 문항을 고쳤다. 같은 트랜잭션 안에서 발행된다.
 * 학교별 DB(schooldb)가 받아서 이 기출의 기여를 다시 계산한다.
 */
public record PastExamChangedEvent(Long pastExamId) {
}
