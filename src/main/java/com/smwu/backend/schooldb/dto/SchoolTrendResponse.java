package com.smwu.backend.schooldb.dto;

import com.smwu.backend.pastexam.extraction.QuestionType;
import com.smwu.backend.schooldb.service.SchoolTrendCalculator.Trend;
import com.smwu.backend.schooldb.service.SchoolTrendCalculator.YearTrend;

import java.util.List;
import java.util.Map;

/**
 * 학교 + 학년의 누적 출제 경향 (모든 학원 기출의 통계, 원문 없음).
 *
 * @param basis              화면 표시용 근거 (예: 학교 DB 기출 3회분 · 학원 2곳 기준)
 * @param confidence         LOW(1회분) / MEDIUM(2~3회분) / HIGH(4회분 이상). 회차가 없으면 NONE
 * @param latestExam         가장 최근 회차 (예: 2025년 1학기 중간)
 * @param subjectiveRatio    서술형 문항 비중 (최근 회차 가중)
 * @param subjectivePointsRatio 서술형 배점 비중 (배점이 있는 회차만, 없으면 null)
 * @param types              유형별 비중 (많은 순)
 * @param typeMixPerPassage  지문당 서술형 유형 구성 (문제 생성 기본값)
 * @param highlights         변화 (예: 어구 배열: 최근 3회 연속 출제)
 */
public record SchoolTrendResponse(
        Long schoolId,
        String schoolName,
        int grade,
        int examCount,
        int contributorCount,
        String basis,
        Confidence confidence,
        String latestExam,
        double subjectiveRatio,
        Double subjectivePointsRatio,
        double averageQuestions,
        List<TypeRatio> types,
        Map<QuestionType, Integer> typeMixPerPassage,
        List<YearTrend> byYear,
        List<String> highlights
) {

    public enum Confidence {
        NONE, LOW, MEDIUM, HIGH;

        public static Confidence of(int examCount) {
            return examCount == 0 ? NONE : examCount == 1 ? LOW : examCount <= 3 ? MEDIUM : HIGH;
        }
    }

    public record TypeRatio(QuestionType type, String label, double ratio) {
    }

    public static SchoolTrendResponse of(Long schoolId, String schoolName, int grade, int contributorCount, String latestExam,
                                         Trend trend) {
        String basis = trend.examCount() == 0 ? "아직 학교 DB에 기출이 없습니다."
                : "학교 DB 기출 %d회분 · 학원 %d곳 기준".formatted(trend.examCount(), contributorCount);
        List<TypeRatio> types = trend.typeRatios().entrySet().stream()
                .map(e -> new TypeRatio(e.getKey(), e.getKey().getLabel(), e.getValue()))
                .toList();
        return new SchoolTrendResponse(schoolId, schoolName, grade, trend.examCount(), contributorCount, basis,
                Confidence.of(trend.examCount()), latestExam, trend.weightedSubjectiveRatio(), trend.weightedPointsRatio(),
                trend.averageQuestions(), types, trend.typeMixPerPassage(), trend.byYear(), trend.highlights());
    }
}
