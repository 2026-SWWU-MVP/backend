package com.smwu.backend.profile.service;

import com.smwu.backend.pastexam.domain.PastQuestion;
import com.smwu.backend.pastexam.extraction.ExtractedExam.QuestionSection;
import com.smwu.backend.pastexam.extraction.QuestionType;
import com.smwu.backend.profile.domain.ProfileStats;
import com.smwu.backend.profile.domain.ProfileStats.ConditionCount;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static com.smwu.backend.pastexam.extraction.ExtractedExam.QuestionSection.OBJECTIVE;
import static com.smwu.backend.pastexam.extraction.ExtractedExam.QuestionSection.SUBJECTIVE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

class ProfileStatsCalculatorTest {

    @Test
    void 문항_수_비율_배점_비중_유형별_개수를_집계한다() {
        List<PastQuestion> questions = List.of(
                q(OBJECTIVE, QuestionType.OBJ_GRAMMAR, 3.0, List.of()),
                q(OBJECTIVE, QuestionType.OBJ_BLANK, 3.0, List.of()),
                q(OBJECTIVE, QuestionType.OBJ_GRAMMAR, 2.0, List.of()),
                q(SUBJECTIVE, QuestionType.SENTENCE_ORDER, 5.0, List.of("1. [보기]의 모든 어구를 한 번씩 사용할 것.", "단어를 추가하지 말 것")),
                q(SUBJECTIVE, QuestionType.SENTENCE_ORDER, 5.0, List.of("[보기]의 모든 어구를  한 번씩 사용할 것", "[보기]의 모든 어구를 한 번씩 사용할 것.")),
                q(SUBJECTIVE, QuestionType.SUMMARY_BLANK, 2.0, List.of("단어를 추가하지 말 것.")));

        ProfileStats stats = ProfileStatsCalculator.calculate(2, questions);

        assertThat(stats.examCount()).isEqualTo(2);
        assertThat(stats.totalQuestions()).isEqualTo(6);
        assertThat(stats.objectiveCount()).isEqualTo(3);
        assertThat(stats.subjectiveCount()).isEqualTo(3);
        assertThat(stats.subjectiveRatio()).isEqualTo(0.5);
        assertThat(stats.subjectivePointsRatio()).isEqualTo(0.6);
        assertThat(stats.typeCounts()).containsExactly(
                entry(QuestionType.OBJ_BLANK, 1), entry(QuestionType.OBJ_GRAMMAR, 2),
                entry(QuestionType.SUMMARY_BLANK, 1), entry(QuestionType.SENTENCE_ORDER, 2));
        // 번호·공백·마침표를 정규화하고, 한 문항 안의 중복은 1번으로 센다
        assertThat(stats.frequentConditions()).containsExactly(
                new ConditionCount("[보기]의 모든 어구를 한 번씩 사용할 것", 2),
                new ConditionCount("단어를 추가하지 말 것", 2));
    }

    @Test
    void 배점이_없는_문항이_있으면_배점_비중은_null() {
        ProfileStats stats = ProfileStatsCalculator.calculate(1, List.of(
                q(OBJECTIVE, QuestionType.OBJ_BLANK, 3.0, List.of()),
                q(SUBJECTIVE, QuestionType.SUMMARY_BLANK, null, List.of())));

        assertThat(stats.subjectivePointsRatio()).isNull();
    }

    @Test
    void 순서가_달라도_같은_기출이면_같은_통계() {
        List<PastQuestion> questions = new ArrayList<>(List.of(
                q(SUBJECTIVE, QuestionType.SUMMARY_BLANK, 4.0, List.of("한 단어로 쓸 것", "본문에서 찾을 것")),
                q(SUBJECTIVE, QuestionType.SUMMARY_BLANK, 4.0, List.of("본문에서 찾을 것", "한 단어로 쓸 것")),
                q(OBJECTIVE, QuestionType.OBJ_VOCAB, 2.5, List.of())));
        ProfileStats first = ProfileStatsCalculator.calculate(1, questions);
        Collections.reverse(questions);

        assertThat(ProfileStatsCalculator.calculate(1, questions)).isEqualTo(first);
    }

    @Test
    void 지문당_유형_구성은_기출_서답형_비율을_3문항으로_나눈다() {
        // 어구 배열 4, 요약 빈칸 2 → 3 × (4/6, 2/6) = 2, 1
        Map<QuestionType, Integer> mix = ProfileStatsCalculator.typeMixPerPassage(List.of(
                q(SUBJECTIVE, QuestionType.SENTENCE_ORDER, null, List.of()),
                q(SUBJECTIVE, QuestionType.SENTENCE_ORDER, null, List.of()),
                q(SUBJECTIVE, QuestionType.SENTENCE_ORDER, null, List.of()),
                q(SUBJECTIVE, QuestionType.SENTENCE_ORDER, null, List.of()),
                q(SUBJECTIVE, QuestionType.SUMMARY_BLANK, null, List.of()),
                q(SUBJECTIVE, QuestionType.SUMMARY_BLANK, null, List.of()),
                q(SUBJECTIVE, QuestionType.SUBJ_SHORT_ANSWER, null, List.of()),
                q(OBJECTIVE, QuestionType.OBJ_GRAMMAR, null, List.of())));

        assertThat(mix).containsExactly(entry(QuestionType.SUMMARY_BLANK, 1), entry(QuestionType.SENTENCE_ORDER, 2));
    }

    @Test
    void 유형이_4개로_고르면_기출에_많이_나온_유형부터_3자리를_채운다() {
        Map<QuestionType, Integer> mix = ProfileStatsCalculator.typeMixPerPassage(List.of(
                q(SUBJECTIVE, QuestionType.GUIDED_WRITING, null, List.of()),
                q(SUBJECTIVE, QuestionType.GUIDED_WRITING, null, List.of()),
                q(SUBJECTIVE, QuestionType.SUMMARY_BLANK, null, List.of()),
                q(SUBJECTIVE, QuestionType.SENTENCE_ORDER, null, List.of()),
                q(SUBJECTIVE, QuestionType.GRAMMAR_FIX, null, List.of())));

        assertThat(mix.values().stream().mapToInt(Integer::intValue).sum()).isEqualTo(3);
        assertThat(mix).containsEntry(QuestionType.GUIDED_WRITING, 1).hasSize(3);
    }

    @Test
    void 생성_가능한_서답형이_없으면_기본_구성() {
        assertThat(ProfileStatsCalculator.typeMixPerPassage(List.of(q(OBJECTIVE, QuestionType.OBJ_BLANK, null, List.of()))))
                .containsExactly(entry(QuestionType.SUMMARY_BLANK, 2), entry(QuestionType.SENTENCE_ORDER, 1));
    }

    private static PastQuestion q(QuestionSection section, QuestionType type, Double points, List<String> conditions) {
        return PastQuestion.builder()
                .pastExamId(1L).orderNo(1).section(section).no(1).type(type)
                .passageCodes(List.of()).stem("발문").conditions(conditions).choices(List.of()).points(points)
                .build();
    }
}
