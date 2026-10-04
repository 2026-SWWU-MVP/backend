package com.smwu.backend.profile.service;

import com.smwu.backend.pastexam.domain.PastQuestion;
import com.smwu.backend.pastexam.extraction.ExtractedExam.QuestionSection;
import com.smwu.backend.pastexam.extraction.QuestionType;
import com.smwu.backend.profile.domain.ProfileStats;
import com.smwu.backend.profile.domain.ProfileStats.ConditionCount;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 기출 문항으로 출제 통계와 지문당 유형 구성을 계산한다. LLM을 쓰지 않으므로 같은 입력이면 항상 같은 결과가 나온다.
 */
public final class ProfileStatsCalculator {

    /** 문제 생성이 가능한 서답형 유형 (문제 생성 #15, #21) */
    public static final List<QuestionType> GENERATABLE_TYPES = List.of(
            QuestionType.SUMMARY_BLANK, QuestionType.SENTENCE_ORDER, QuestionType.GRAMMAR_FIX, QuestionType.GUIDED_WRITING);

    /** 출력예시 기준 지문 1개당 서답형 2~3문항 → 기본 3문항 */
    static final int QUESTIONS_PER_PASSAGE = 3;
    static final Map<QuestionType, Integer> DEFAULT_TYPE_MIX = Map.of(QuestionType.SUMMARY_BLANK, 2, QuestionType.SENTENCE_ORDER, 1);

    private static final int MIN_CONDITION_COUNT = 2;
    private static final int MAX_CONDITIONS = 10;

    private ProfileStatsCalculator() {
    }

    public static ProfileStats calculate(int examCount, List<PastQuestion> questions) {
        int subjective = (int) questions.stream().filter(q -> q.getSection() == QuestionSection.SUBJECTIVE).count();
        int objective = questions.size() - subjective;

        Map<QuestionType, Integer> typeCounts = new EnumMap<>(QuestionType.class);
        questions.forEach(q -> typeCounts.merge(q.getType(), 1, Integer::sum));

        return new ProfileStats(
                examCount,
                questions.size(),
                objective,
                subjective,
                questions.isEmpty() ? 0 : round(subjective / (double) questions.size()),
                subjectivePointsRatio(questions),
                new LinkedHashMap<>(typeCounts),
                frequentConditions(questions));
    }

    /**
     * 기출 서답형의 생성 가능 유형 비율을 지문당 3문항으로 나눈다 (최대 나머지 방식).
     * 해당 유형이 기출에 하나도 없으면 출력예시 기준 기본값(요약문 빈칸 2 + 어구 배열 1)을 쓴다.
     */
    public static Map<QuestionType, Integer> typeMixPerPassage(List<PastQuestion> questions) {
        Map<QuestionType, Double> counts = new EnumMap<>(QuestionType.class);
        questions.stream()
                .filter(q -> q.getSection() == QuestionSection.SUBJECTIVE)
                .forEach(q -> counts.merge(q.getType(), 1.0, Double::sum));
        return apportionMix(counts);
    }

    /**
     * 유형별 (가중) 출제 수를 지문당 3문항으로 나눈다. 생성할 수 없는 유형은 빼고 계산한다.
     * 학교 DB 경향(#39)처럼 회차마다 가중치를 준 값도 그대로 넣을 수 있다.
     */
    public static Map<QuestionType, Integer> apportionMix(Map<QuestionType, Double> weightedCounts) {
        Map<QuestionType, Double> counts = new EnumMap<>(QuestionType.class);
        weightedCounts.forEach((type, count) -> {
            if (GENERATABLE_TYPES.contains(type) && count > 0) {
                counts.put(type, count);
            }
        });
        double total = counts.values().stream().mapToDouble(Double::doubleValue).sum();
        if (total == 0) {
            return orderedMix(new EnumMap<>(DEFAULT_TYPE_MIX));
        }

        Map<QuestionType, Integer> mix = new EnumMap<>(QuestionType.class);
        Map<QuestionType, Double> remainders = new EnumMap<>(QuestionType.class);
        int assigned = 0;
        for (Map.Entry<QuestionType, Double> e : counts.entrySet()) {
            double quota = QUESTIONS_PER_PASSAGE * e.getValue() / total;
            int floor = (int) Math.floor(quota);
            mix.put(e.getKey(), floor);
            remainders.put(e.getKey(), quota - floor);
            assigned += floor;
        }
        // 남은 자리는 나머지가 큰 순 → 기출 개수가 많은 순 → 유형 순서로 배정
        List<QuestionType> order = new ArrayList<>(counts.keySet());
        order.sort(Comparator.<QuestionType>comparingDouble(remainders::get).reversed()
                .thenComparing(Comparator.<QuestionType>comparingDouble(counts::get).reversed())
                .thenComparing(Comparator.naturalOrder()));
        for (int i = 0; assigned < QUESTIONS_PER_PASSAGE; i++, assigned++) {
            mix.merge(order.get(i % order.size()), 1, Integer::sum);
        }
        mix.values().removeIf(v -> v == 0);
        return orderedMix(mix);
    }

    private static Map<QuestionType, Integer> orderedMix(Map<QuestionType, Integer> mix) {
        Map<QuestionType, Integer> ordered = new LinkedHashMap<>();
        new EnumMap<>(mix).forEach(ordered::put);
        return ordered;
    }

    /** 배점이 모두 있을 때만 계산한다 (일부만 있으면 비중이 왜곡되므로 null) */
    private static Double subjectivePointsRatio(List<PastQuestion> questions) {
        if (questions.isEmpty() || questions.stream().anyMatch(q -> q.getPoints() == null)) {
            return null;
        }
        double total = questions.stream().mapToDouble(PastQuestion::getPoints).sum();
        double subjective = questions.stream()
                .filter(q -> q.getSection() == QuestionSection.SUBJECTIVE)
                .mapToDouble(PastQuestion::getPoints).sum();
        return total == 0 ? null : round(subjective / total);
    }

    /** 서답형 [조건] 문구를 정규화해 몇 문항에서 나왔는지 센다 (한 문항 안의 중복은 1번) */
    private static List<ConditionCount> frequentConditions(List<PastQuestion> questions) {
        Map<String, Integer> counts = new HashMap<>();
        for (PastQuestion q : questions) {
            if (q.getSection() != QuestionSection.SUBJECTIVE) {
                continue;
            }
            Set<String> unique = new LinkedHashSet<>();
            q.getConditions().forEach(c -> unique.add(normalizeCondition(c)));
            unique.remove("");
            unique.forEach(c -> counts.merge(c, 1, Integer::sum));
        }
        return counts.entrySet().stream()
                .filter(e -> e.getValue() >= MIN_CONDITION_COUNT)
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
                .limit(MAX_CONDITIONS)
                .map(e -> new ConditionCount(e.getKey(), e.getValue()))
                .toList();
    }

    static String normalizeCondition(String condition) {
        return condition.strip()
                .replaceAll("^[0-9]+[.)]\\s*", "")
                .replaceAll("\\s+", " ")
                .replaceAll("[.。]+$", "")
                .strip();
    }

    private static double round(double value) {
        return Math.round(value * 1000) / 1000.0;
    }
}
