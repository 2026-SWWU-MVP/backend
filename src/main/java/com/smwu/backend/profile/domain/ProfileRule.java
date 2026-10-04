package com.smwu.backend.profile.domain;

import java.util.List;

/**
 * 출제 규칙 한 개.
 *
 * @param id                  r1, r2 ... (프로필 버전 안에서 고유)
 * @param text                규칙 내용 (한국어)
 * @param category            규칙 분류
 * @param source              PAST_EXAM: 내 학원 기출 분석 / SCHOOL_DB: 학교 DB 경향(다른 학원 기출 통계) / TEACHER: 강사 의견
 * @param evidenceQuestionIds 근거 기출 문항(PastQuestion) ID. source=PAST_EXAM이면 1개 이상
 * @param overridden          강사 의견과 충돌해서 이번 시험에서는 적용하지 않는 기출 규칙 (#13)
 * @param noteIndex           source=TEACHER일 때 근거가 된 teacherNotes 위치 (#13)
 */
public record ProfileRule(
        String id,
        String text,
        RuleCategory category,
        RuleSource source,
        List<Long> evidenceQuestionIds,
        boolean overridden,
        Integer noteIndex
) {

    public enum RuleSource {
        PAST_EXAM("[기출]"), SCHOOL_DB("[학교 DB]"), TEACHER("[강사]");

        private final String label;

        RuleSource(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }

        /** 분석에서 나온 규칙 (강사 의견으로 비활성화할 수 있다) */
        public boolean isAnalysis() {
            return this != TEACHER;
        }
    }

    public enum RuleCategory {
        /** 어떤 서술형 유형이 나오는지 */
        QUESTION_TYPE,
        /** [조건] 형식 (단어 수, 어형 변화 허용 등) */
        CONDITION,
        /** 지문 활용 방식 (본문 그대로, 변형, 요약문 등) */
        PASSAGE_USE,
        /** 문법·어휘 출제 포인트 */
        GRAMMAR_POINT,
        /** 배점, 부분 점수 */
        SCORING,
        OTHER
    }
}
