package com.smwu.backend.schooldb.service;

import com.smwu.backend.pastexam.domain.ExamType;
import com.smwu.backend.pastexam.extraction.QuestionType;
import com.smwu.backend.profile.service.ProfileStatsCalculator;
import com.smwu.backend.schooldb.domain.ExamRoundStats;
import com.smwu.backend.schooldb.domain.SchoolExam;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 학교 DB 회차들을 합쳐 누적 경향을 계산한다. LLM 없이 코드로만 계산하므로 같은 회차 구성이면 결과가 같다.
 * <ul>
 *   <li>가중치: 가장 최근 회차의 연도 기준 같은 해 ×1.0, 1년 전 ×0.5, 그 이전 ×0.25</li>
 *   <li>변화: 연도별 서술형 비중 변화(10%p 이상), 서술형 유형의 연속 출제·첫 출제·빠짐</li>
 * </ul>
 */
public final class SchoolTrendCalculator {

    static final double SUBJECTIVE_CHANGE_THRESHOLD = 0.10;

    private SchoolTrendCalculator() {
    }

    /**
     * @param weightedSubjectiveRatio 가중 서술형 문항 비중
     * @param weightedPointsRatio     배점이 있는 회차만의 가중 서술형 배점 비중 (없으면 null)
     * @param typeRatios              전체 문항 중 유형별 가중 비중 (많은 순)
     * @param typeMixPerPassage       가중 서술형 유형으로 정한 지문당 유형 구성 (문제 생성 기본값)
     * @param averageQuestions        회차당 평균 문항 수
     * @param byYear                  연도별 (오래된 순)
     * @param highlights              강사에게 보여줄 변화 (예: 어구 배열 4회 연속 출제)
     */
    public record Trend(
            int examCount,
            double weightedSubjectiveRatio,
            Double weightedPointsRatio,
            Map<QuestionType, Double> typeRatios,
            Map<QuestionType, Integer> typeMixPerPassage,
            double averageQuestions,
            List<YearTrend> byYear,
            List<String> highlights
    ) {
    }

    public record YearTrend(int year, int examCount, double subjectiveRatio, Map<QuestionType, Integer> typeCounts) {
    }

    public static Trend calculate(List<SchoolExam> rounds) {
        List<SchoolExam> sorted = rounds.stream().filter(r -> r.getStats() != null)
                .sorted(Comparator.comparingInt(SchoolExam::order)).toList();
        if (sorted.isEmpty()) {
            return new Trend(0, 0, null, Map.of(), ProfileStatsCalculator.apportionMix(Map.of()), 0, List.of(), List.of());
        }
        int latestYear = sorted.get(sorted.size() - 1).getExamYear();

        double weightSum = 0;
        double questions = 0;
        double subjective = 0;
        double pointsWeight = 0;
        double points = 0;
        Map<QuestionType, Double> typeWeighted = new EnumMap<>(QuestionType.class);
        Map<QuestionType, Double> subjectiveTypeWeighted = new EnumMap<>(QuestionType.class);
        for (SchoolExam round : sorted) {
            ExamRoundStats s = round.getStats();
            double w = weight(latestYear - round.getExamYear());
            weightSum += w;
            questions += w * s.totalQuestions();
            subjective += w * s.subjectiveCount();
            if (s.subjectivePointsRatio() != null) {
                pointsWeight += w;
                points += w * s.subjectivePointsRatio();
            }
            s.typeCounts().forEach((type, count) -> {
                typeWeighted.merge(type, w * count, Double::sum);
                if (isSubjective(type)) {
                    subjectiveTypeWeighted.merge(type, w * count, Double::sum);
                }
            });
        }
        double totalQuestions = questions;
        Map<QuestionType, Double> typeRatios = new LinkedHashMap<>();
        typeWeighted.entrySet().stream()
                .sorted(Map.Entry.<QuestionType, Double>comparingByValue().reversed())
                .forEach(e -> typeRatios.put(e.getKey(), round(e.getValue() / totalQuestions)));

        List<YearTrend> byYear = byYear(sorted);
        return new Trend(
                sorted.size(),
                questions == 0 ? 0 : round(subjective / questions),
                pointsWeight == 0 ? null : round(points / pointsWeight),
                typeRatios,
                ProfileStatsCalculator.apportionMix(subjectiveTypeWeighted),
                round(questions / weightSum),
                byYear,
                highlights(sorted, byYear));
    }

    static double weight(int yearsAgo) {
        return yearsAgo <= 0 ? 1.0 : yearsAgo == 1 ? 0.5 : 0.25;
    }

    private static List<YearTrend> byYear(List<SchoolExam> sorted) {
        Map<Integer, List<SchoolExam>> years = new TreeMap<>();
        sorted.forEach(r -> years.computeIfAbsent(r.getExamYear(), y -> new ArrayList<>()).add(r));
        List<YearTrend> result = new ArrayList<>();
        years.forEach((year, list) -> {
            int total = list.stream().mapToInt(r -> r.getStats().totalQuestions()).sum();
            int subjective = list.stream().mapToInt(r -> r.getStats().subjectiveCount()).sum();
            Map<QuestionType, Integer> counts = new EnumMap<>(QuestionType.class);
            list.forEach(r -> r.getStats().typeCounts().forEach((t, c) -> counts.merge(t, c, Integer::sum)));
            result.add(new YearTrend(year, list.size(), total == 0 ? 0 : round(subjective / (double) total), new LinkedHashMap<>(counts)));
        });
        return result;
    }

    private static List<String> highlights(List<SchoolExam> sorted, List<YearTrend> byYear) {
        List<String> result = new ArrayList<>();
        if (byYear.size() >= 2) {
            YearTrend first = byYear.get(0);
            YearTrend last = byYear.get(byYear.size() - 1);
            double diff = last.subjectiveRatio() - first.subjectiveRatio();
            if (Math.abs(diff) >= SUBJECTIVE_CHANGE_THRESHOLD) {
                result.add("서술형 비중이 %d년 %s에서 %d년 %s로 %s".formatted(first.year(), percent(first.subjectiveRatio()),
                        last.year(), percent(last.subjectiveRatio()), diff > 0 ? "늘었습니다." : "줄었습니다."));
            }
        }
        if (sorted.size() < 2) {
            return result;
        }
        SchoolExam latest = sorted.get(sorted.size() - 1);
        SchoolExam previous = sorted.get(sorted.size() - 2);
        for (QuestionType type : QuestionType.values()) {
            if (!isSubjective(type) || type == QuestionType.SUBJ_OTHER) {
                continue;
            }
            int streak = 0;
            for (int i = sorted.size() - 1; i >= 0 && has(sorted.get(i), type); i--) {
                streak++;
            }
            boolean before = sorted.subList(0, sorted.size() - 1).stream().anyMatch(r -> has(r, type));
            if (streak == sorted.size()) {
                result.add("%s: 기출 %d회 모두 출제".formatted(type.getLabel(), streak));
            } else if (streak >= 2) {
                result.add("%s: 최근 %d회 연속 출제".formatted(type.getLabel(), streak));
            } else if (streak == 1 && !before) {
                result.add("%s: 최근 회차(%s)에 처음 출제".formatted(type.getLabel(), label(latest)));
            } else if (streak == 0 && has(previous, type)) {
                result.add("%s: 직전 회차(%s)에는 있었지만 최근 회차(%s)에는 없음".formatted(type.getLabel(), label(previous), label(latest)));
            }
        }
        return result;
    }

    private static boolean has(SchoolExam round, QuestionType type) {
        return round.getStats().typeCounts().getOrDefault(type, 0) > 0;
    }

    static boolean isSubjective(QuestionType type) {
        return !type.name().startsWith("OBJ_");
    }

    public static String label(SchoolExam round) {
        return "%d년 %d학기 %s".formatted(round.getExamYear(), round.getSemester(), round.getExamType() == ExamType.FINAL ? "기말" : "중간");
    }

    private static String percent(double ratio) {
        return Math.round(ratio * 100) + "%";
    }

    private static double round(double value) {
        return Math.round(value * 1000) / 1000.0;
    }
}
