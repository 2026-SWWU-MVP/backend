package com.smwu.backend.profile.service;

import com.smwu.backend.pastexam.extraction.QuestionType;
import com.smwu.backend.profile.domain.ProfileRule;
import com.smwu.backend.profile.domain.ProfileRule.RuleSource;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** 프로필 규칙·유형 구성 공통 처리 (ID 발급, 프롬프트용 설명, 유형 구성 검증) */
public final class ProfileRules {

    static final int MIN_MIX_TOTAL = 1;
    static final int MAX_MIX_TOTAL = 5;

    private ProfileRules() {
    }

    /** 기존 규칙 id(r1, r2 ...) 중 가장 큰 번호 + 1 */
    public static String nextRuleId(List<ProfileRule> rules) {
        int max = rules.stream()
                .map(ProfileRule::id)
                .filter(id -> id != null && id.matches("r\\d+"))
                .mapToInt(id -> Integer.parseInt(id.substring(1)))
                .max().orElse(0);
        return "r" + (max + 1);
    }

    /**
     * 프롬프트용 규칙 목록. 예) r1 [기출] [CONDITION] 요약문 빈칸은 ... (근거: Q1, Q3)
     *
     * @param evidenceKeys 문항 ID → 이번 요청의 키(Q1 ...). 없으면 근거 표시를 생략
     */
    static String describeRules(List<ProfileRule> rules, Map<Long, String> evidenceKeys) {
        if (rules.isEmpty()) {
            return "(규칙 없음)";
        }
        return rules.stream().map(r -> {
            StringBuilder sb = new StringBuilder()
                    .append(r.id()).append(' ')
                    .append(r.source().label()).append(' ')
                    .append('[').append(r.category()).append("] ")
                    .append(r.text());
            if (r.overridden()) {
                sb.append(" (이번 시험에서 비활성화됨)");
            }
            if (evidenceKeys != null && !r.evidenceQuestionIds().isEmpty()) {
                String keys = r.evidenceQuestionIds().stream()
                        .map(evidenceKeys::get)
                        .filter(k -> k != null)
                        .collect(Collectors.joining(", "));
                if (!keys.isEmpty()) {
                    sb.append(" (근거: ").append(keys).append(')');
                }
            }
            return sb.toString();
        }).collect(Collectors.joining("\n"));
    }

    /** 예) 요약문 빈칸(SUMMARY_BLANK) 2, 어구 배열(SENTENCE_ORDER) 1 */
    static String describeTypeMix(Map<QuestionType, Integer> mix) {
        return ProfileStatsCalculator.GENERATABLE_TYPES.stream()
                .map(t -> t.getLabel() + "(" + t.name() + ") " + mix.getOrDefault(t, 0))
                .collect(Collectors.joining(", "));
    }

    /**
     * 지문당 유형 구성 검증: 생성 가능한 서답형 유형만, 유형별 0~5, 합계 1~5. 0인 유형은 뺀다.
     *
     * @throws IllegalArgumentException 규칙을 어기면 (메시지는 사용자에게 보여줄 수 있는 문장)
     */
    public static Map<QuestionType, Integer> validateTypeMix(Map<QuestionType, Integer> mix) {
        Map<QuestionType, Integer> cleaned = new EnumMap<>(QuestionType.class);
        for (Map.Entry<QuestionType, Integer> e : mix.entrySet()) {
            if (e.getKey() == null || !ProfileStatsCalculator.GENERATABLE_TYPES.contains(e.getKey())) {
                throw new IllegalArgumentException("지문당 유형 구성에는 요약문 빈칸, 어구 배열, 어법 오류 수정, 조건 영작만 넣을 수 있습니다.");
            }
            int count = e.getValue() == null ? 0 : e.getValue();
            if (count < 0 || count > MAX_MIX_TOTAL) {
                throw new IllegalArgumentException("유형별 문항 수는 0~" + MAX_MIX_TOTAL + "입니다.");
            }
            if (count > 0) {
                cleaned.put(e.getKey(), count);
            }
        }
        int total = cleaned.values().stream().mapToInt(Integer::intValue).sum();
        if (total < MIN_MIX_TOTAL || total > MAX_MIX_TOTAL) {
            throw new IllegalArgumentException("지문 1개당 문항 수 합계는 " + MIN_MIX_TOTAL + "~" + MAX_MIX_TOTAL + "입니다.");
        }
        return new LinkedHashMap<>(cleaned);
    }
}
