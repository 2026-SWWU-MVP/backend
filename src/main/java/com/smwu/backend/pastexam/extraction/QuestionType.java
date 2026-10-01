package com.smwu.backend.pastexam.extraction;

/**
 * 기출 문항 유형. 기출 분석(출제 패턴 집계)용이라 생성 가능한 유형보다 넓게 분류한다.
 * 서술형 4종(SUMMARY_BLANK, SENTENCE_ORDER, GRAMMAR_FIX, GUIDED_WRITING)은 문제 생성 유형과 이름이 같다.
 */
public enum QuestionType {

    // 객관식
    OBJ_MAIN_IDEA,      // 주제·제목·요지·목적
    OBJ_DETAIL,         // 내용 일치·불일치
    OBJ_BLANK,          // 빈칸 추론
    OBJ_GRAMMAR,        // 어법상 옳은/틀린 것
    OBJ_VOCAB,          // 문맥상 어휘
    OBJ_ORDER,          // 글의 순서
    OBJ_INSERT,         // 문장 삽입
    OBJ_IRRELEVANT,     // 흐름과 무관한 문장
    OBJ_REFERENCE,      // 지칭 대상
    OBJ_SUMMARY,        // 요약문 완성 (선택지)
    OBJ_OTHER,

    // 서술형
    SUMMARY_BLANK,      // 요약문 빈칸 쓰기
    SENTENCE_ORDER,     // 어구 배열
    GRAMMAR_FIX,        // 어법 오류 찾아 고치기
    GUIDED_WRITING,     // 조건·제시어 영작
    SUBJ_SHORT_ANSWER,  // 단답형 (본문에서 찾아 쓰기 등)
    SUBJ_OTHER
}
