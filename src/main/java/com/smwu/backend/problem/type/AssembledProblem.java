package com.smwu.backend.problem.type;

import com.smwu.backend.pastexam.extraction.QuestionType;

import java.util.List;

/**
 * LLM이 만든 내용에 코드가 발문·[조건]·빈칸 표기·[보기] 순서를 붙여 완성한 문제.
 *
 * @param stem        발문 (한국어, 코드 템플릿)
 * @param conditions  [조건] (코드 템플릿)
 * @param body        문제 본문 (요약문 등). 없으면 null
 * @param choices     [보기] 항목 (어구 배열은 코드가 섞은 순서). 없으면 빈 목록
 * @param answer      구조화된 정답
 * @param answerText  정답지에 그대로 찍을 문자열. 예) (1) lifeless   (2) revitalize
 * @param explanation 해설 (한국어)
 * @param evidence    근거가 되는 원문 문장
 */
public record AssembledProblem(
        QuestionType type,
        String stem,
        List<String> conditions,
        String body,
        List<String> choices,
        ProblemAnswer answer,
        String answerText,
        String explanation,
        String evidence
) {
}
