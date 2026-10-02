package com.smwu.backend.pastexam.extraction;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 기출 문항 유형. 기출 분석(출제 패턴 집계)용이라 생성 가능한 유형보다 넓게 분류한다.
 * 서술형 4종(SUMMARY_BLANK, SENTENCE_ORDER, GRAMMAR_FIX, GUIDED_WRITING)은 문제 생성 유형과 이름이 같다.
 * label은 강사 화면과 LLM 프롬프트에 쓰는 한국어 이름이다.
 */
@Getter
@RequiredArgsConstructor
public enum QuestionType {

    // 객관식
    OBJ_MAIN_IDEA("주제·제목·요지"),
    OBJ_DETAIL("내용 일치"),
    OBJ_BLANK("빈칸 추론"),
    OBJ_GRAMMAR("어법"),
    OBJ_VOCAB("어휘"),
    OBJ_ORDER("글의 순서"),
    OBJ_INSERT("문장 삽입"),
    OBJ_IRRELEVANT("무관한 문장"),
    OBJ_REFERENCE("지칭 대상"),
    OBJ_SUMMARY("요약문 완성(객관식)"),
    /** 듣기 (지문이 시험지에 없음, 생성 대상 아님) */
    OBJ_LISTENING("듣기"),
    OBJ_OTHER("기타 객관식"),

    // 서술형
    SUMMARY_BLANK("요약문 빈칸"),
    SENTENCE_ORDER("어구 배열"),
    GRAMMAR_FIX("어법 오류 수정"),
    GUIDED_WRITING("조건 영작"),
    /** 본문에서 찾아 쓰기, 지칭 대상 쓰기 등 */
    SUBJ_SHORT_ANSWER("단답형"),
    SUBJ_OTHER("기타 서술형");

    private final String label;
}
