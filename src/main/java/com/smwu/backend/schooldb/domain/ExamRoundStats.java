package com.smwu.backend.schooldb.domain;

import com.smwu.backend.pastexam.domain.PastQuestion;
import com.smwu.backend.pastexam.extraction.QuestionType;
import com.smwu.backend.profile.domain.ProfileStats;
import com.smwu.backend.profile.service.ProfileStatsCalculator;

import java.util.List;
import java.util.Map;

/**
 * 학교 DB에 공유하는 기출 1회분 통계. 다른 학원도 보므로 숫자만 담는다 (지문·발문·[조건] 원문 없음).
 *
 * @param subjectivePointsRatio 배점 표시가 있을 때만 서술형 배점 비중, 없으면 null
 * @param typeMixPerPassage     지문 1개당 서술형 유형 구성 (문제 생성 기본값)
 * @param passageCount          지문 수
 */
public record ExamRoundStats(
        int totalQuestions,
        int objectiveCount,
        int subjectiveCount,
        double subjectiveRatio,
        Double subjectivePointsRatio,
        Map<QuestionType, Integer> typeCounts,
        Map<QuestionType, Integer> typeMixPerPassage,
        int passageCount
) {

    public static ExamRoundStats of(List<PastQuestion> questions, int passageCount) {
        ProfileStats stats = ProfileStatsCalculator.calculate(1, questions);
        return new ExamRoundStats(stats.totalQuestions(), stats.objectiveCount(), stats.subjectiveCount(),
                stats.subjectiveRatio(), stats.subjectivePointsRatio(), stats.typeCounts(),
                ProfileStatsCalculator.typeMixPerPassage(questions), passageCount);
    }
}
