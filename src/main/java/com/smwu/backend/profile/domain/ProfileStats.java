package com.smwu.backend.profile.domain;

import com.smwu.backend.pastexam.extraction.QuestionType;

import java.util.List;
import java.util.Map;

/**
 * 기출에서 코드가 집계한 사실. 같은 기출이면 항상 같은 값이 나오고, 강사 의견으로 바뀌지 않는다.
 *
 * @param examCount             분석한 기출 시험지 수
 * @param totalQuestions        전체 문항 수
 * @param objectiveCount        객관식 문항 수
 * @param subjectiveCount       서답형 문항 수
 * @param subjectiveRatio       서답형 문항 비율 (0~1, 소수 셋째 자리 반올림)
 * @param subjectivePointsRatio 배점 기준 서답형 비중 (0~1). 배점이 없는 문항이 있으면 null
 * @param typeCounts            유형별 문항 수 (시험지 전체 합)
 * @param frequentConditions    서답형 [조건]에서 2번 이상 나온 문구 (많은 순, 최대 10개)
 * @param schoolDbExamCount     함께 반영한 학교 DB 회차 수 (다른 학원 기출, 내 기출과 같은 회차 제외). 없으면 null
 */
public record ProfileStats(
        int examCount,
        int totalQuestions,
        int objectiveCount,
        int subjectiveCount,
        double subjectiveRatio,
        Double subjectivePointsRatio,
        Map<QuestionType, Integer> typeCounts,
        List<ConditionCount> frequentConditions,
        Integer schoolDbExamCount
) {

    public record ConditionCount(String text, int count) {
    }
}
