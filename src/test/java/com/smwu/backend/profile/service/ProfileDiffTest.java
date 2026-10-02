package com.smwu.backend.profile.service;

import com.smwu.backend.pastexam.extraction.QuestionType;
import com.smwu.backend.profile.domain.ProfileRule;
import com.smwu.backend.profile.domain.ProfileRule.RuleCategory;
import com.smwu.backend.profile.domain.ProfileRule.RuleSource;
import com.smwu.backend.profile.domain.TeacherNote;
import com.smwu.backend.profile.service.ProfileDiff.Snapshot;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ProfileDiffTest {

    private static final ProfileRule R1 = rule("r1", "요약문 빈칸은 첫 철자를 준다.", RuleSource.PAST_EXAM, List.of(1L), false);
    private static final ProfileRule R2 = rule("r2", "어법 수정이 2문항 이상 나온다.", RuleSource.PAST_EXAM, List.of(2L), false);
    private static final ProfileRule R3 = rule("r3", "단어 수 제한이 있다.", RuleSource.PAST_EXAM, List.of(3L), false);

    @Test
    void 강사_의견_반영_변경점() {
        Snapshot before = new Snapshot(List.of(R1, R2), mix(2, 1), List.of(), List.of(1L, 2L));
        Snapshot after = new Snapshot(
                List.of(R1, rule("r2", R2.text(), RuleSource.PAST_EXAM, List.of(2L), true),
                        rule("r3", "이번 서술형은 어구 배열 위주로 나온다.", RuleSource.TEACHER, List.of(), false)),
                mix(1, 2),
                List.of(new TeacherNote("이번엔 어구 배열 위주래요", true, null)),
                List.of(1L, 2L));

        assertThat(ProfileDiff.describe(before, after)).containsExactly(
                "강사 의견 추가: \"이번엔 어구 배열 위주래요\" (다음 시험에도 적용)",
                "이번 시험에서 비활성화: 어법 수정이 2문항 이상 나온다.",
                "규칙 추가 [강사]: 이번 서술형은 어구 배열 위주로 나온다.",
                "지문당 유형 구성: 요약문 빈칸 2 → 1, 어구 배열 1 → 2");
    }

    @Test
    void 재검토_수정_삭제_근거_변경_대표_문항_변경() {
        Snapshot before = new Snapshot(List.of(R1, R2, R3), mix(2, 1), List.of(), List.of(1L, 2L));
        Snapshot after = new Snapshot(
                List.of(rule("r1", "요약문 빈칸은 첫 철자를 주고 한 단어로 쓰게 한다.", RuleSource.PAST_EXAM, List.of(1L), false),
                        rule("r2", R2.text(), RuleSource.PAST_EXAM, List.of(2L, 4L), false)),
                mix(2, 1), List.of(), List.of(3L));

        assertThat(ProfileDiff.describe(before, after)).containsExactly(
                "규칙 수정: \"요약문 빈칸은 첫 철자를 준다.\" → \"요약문 빈칸은 첫 철자를 주고 한 단어로 쓰게 한다.\"",
                "근거 문항 변경: 어법 수정이 2문항 이상 나온다.",
                "규칙 삭제 [기출]: 단어 수 제한이 있다.",
                "대표 문항 변경 (2개 → 1개)");
    }

    @Test
    void 바뀐_것이_없으면_변경_사항_없음() {
        Snapshot same = new Snapshot(List.of(R1), mix(2, 1), List.of(), List.of(1L));

        assertThat(ProfileDiff.describe(same, same)).containsExactly("변경 사항 없음");
    }

    private static Map<QuestionType, Integer> mix(int summaryBlank, int sentenceOrder) {
        Map<QuestionType, Integer> mix = new LinkedHashMap<>();
        mix.put(QuestionType.SUMMARY_BLANK, summaryBlank);
        mix.put(QuestionType.SENTENCE_ORDER, sentenceOrder);
        return mix;
    }

    private static ProfileRule rule(String id, String text, RuleSource source, List<Long> evidence, boolean overridden) {
        return new ProfileRule(id, text, RuleCategory.OTHER, source, evidence, overridden, source == RuleSource.TEACHER ? 0 : null);
    }
}
