package com.smwu.backend.profile.service;

import com.smwu.backend.pastexam.domain.ExamType;
import com.smwu.backend.pastexam.domain.PastExam;
import com.smwu.backend.pastexam.domain.PastPassage;
import com.smwu.backend.pastexam.domain.PastQuestion;
import com.smwu.backend.pastexam.extraction.ExtractedExam.QuestionSection;
import com.smwu.backend.pastexam.extraction.QuestionType;
import com.smwu.backend.profile.domain.ProfileRule;
import com.smwu.backend.profile.domain.ProfileRule.RuleCategory;
import com.smwu.backend.profile.domain.ProfileRule.RuleSource;
import com.smwu.backend.profile.service.RuleSummarizer.Draft;
import com.smwu.backend.profile.service.RuleSummarizer.RuleDraft;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RuleSummarizerTest {

    private final PastQuestion order = question(11L, 100L, 1, QuestionType.SENTENCE_ORDER, List.of("P1"),
            List.of("[보기]의 어구를 모두 사용할 것", "형태를 바꾸지 말 것"), List.of("having", "a limited budget"));
    private final PastQuestion blank = question(12L, 100L, 2, QuestionType.SUMMARY_BLANK, List.of("P1"),
            List.of("한 단어로 쓸 것"), List.of());
    private final PastQuestion writing = question(21L, 200L, 1, QuestionType.GUIDED_WRITING, List.of(),
            List.of(), List.of());

    @Test
    void 근거가_없거나_중복된_규칙은_버리고_ID로_바꾼다() {
        Draft draft = new Draft(List.of(
                new RuleDraft("어구 배열은 분사구문 문장에서 나온다.", RuleCategory.QUESTION_TYPE, List.of("Q1", "Q1", " Q2 ")),
                new RuleDraft("입력에 없는 문항만 근거로 단 규칙", RuleCategory.OTHER, List.of("Q9")),
                new RuleDraft("   ", RuleCategory.OTHER, List.of("Q1")),
                new RuleDraft("어구 배열은 분사구문 문장에서 나온다.", RuleCategory.QUESTION_TYPE, List.of("Q2")),
                new RuleDraft("분류가 없으면 OTHER", null, List.of("Q3"))),
                List.of("Q2"));

        RuleSummarizer.Result result = RuleSummarizer.toResult(draft, keyed(), "gpt-test");

        assertThat(result.rules()).extracting(ProfileRule::id).containsExactly("r1", "r2");
        assertThat(result.rules().get(0).evidenceQuestionIds()).containsExactly(11L, 12L);
        assertThat(result.rules().get(0).source()).isEqualTo(RuleSource.PAST_EXAM);
        assertThat(result.rules().get(1).category()).isEqualTo(RuleCategory.OTHER);
        assertThat(result.model()).isEqualTo("gpt-test");
    }

    @Test
    void 규칙_문장에_섞인_내부_키는_서답형_번호로_바꾼다() {
        Draft draft = new Draft(List.of(
                new RuleDraft("Q1에서는 단어 추가 없이, Q2와 Q10에서는 한 단어로 쓴다.", RuleCategory.CONDITION, List.of("Q1"))),
                List.of());

        assertThat(RuleSummarizer.toResult(draft, keyed(), "m").rules().get(0).text())
                .isEqualTo("서답형 1번에서는 단어 추가 없이, 서답형 2번와 Q10에서는 한 단어로 쓴다.");
    }

    @Test
    void 규칙은_최대_10개() {
        List<RuleDraft> drafts = new ArrayList<>();
        for (int i = 0; i < 15; i++) {
            drafts.add(new RuleDraft("규칙 " + i, RuleCategory.OTHER, List.of("Q1")));
        }

        assertThat(RuleSummarizer.toResult(new Draft(drafts, List.of()), keyed(), "m").rules()).hasSize(10);
    }

    @Test
    void 대표_문항이_2개보다_적으면_다른_유형부터_채우고_3개를_넘으면_자른다() {
        // Q2(요약 빈칸)만 골랐으면 → 다른 유형 중 [조건]이 많은 Q1(어구 배열)을 채운다
        assertThat(RuleSummarizer.toResult(new Draft(List.of(), List.of("Q2")), keyed(), "m").exampleQuestionIds())
                .containsExactly(12L, 11L);
        // 아무것도 안 골랐으면 [조건]이 많은 순 + 유형 다양하게
        assertThat(RuleSummarizer.toResult(new Draft(List.of(), null), keyed(), "m").exampleQuestionIds())
                .containsExactly(11L, 12L);
        assertThat(RuleSummarizer.toResult(new Draft(List.of(), List.of("Q3", "Q2", "Q1", "Q1", "Q2")), keyed(), "m")
                .exampleQuestionIds()).containsExactly(21L, 12L, 11L);
    }

    @Test
    void 프롬프트에_시험지별_지문과_키가_붙은_문항을_넣는다() {
        PastExam mid = exam(100L, ExamType.MIDTERM);
        PastExam fin = exam(200L, ExamType.FINAL);
        PastPassage used = new PastPassage(100L, "P1", 1, "Old Cities", "As cities age, neighborhoods become lifeless.");
        PastPassage unused = new PastPassage(100L, "P2", 2, null, "Not referenced by subjective questions.");

        String text = RuleSummarizer.describeQuestions(List.of(mid, fin), keyed(), Map.of(100L, List.of(used, unused)));

        assertThat(text)
                .contains("### [E1] 2025년 1학기 중간고사", "### [E2] 2025년 1학기 기말고사")
                .contains("지문 E1-P1 (Old Cities):\nAs cities age")
                .doesNotContain("Not referenced")
                .contains("Q1 | E1 서답형 1번 | 어구 배열 | 5점 | 지문 E1-P1")
                .contains("조건: [보기]의 어구를 모두 사용할 것 / 형태를 바꾸지 말 것")
                .contains("보기: having / a limited budget")
                .contains("Q3 | E2 서답형 1번 | 조건 영작");
    }

    private Map<String, PastQuestion> keyed() {
        Map<String, PastQuestion> keyed = new LinkedHashMap<>();
        keyed.put("Q1", order);
        keyed.put("Q2", blank);
        keyed.put("Q3", writing);
        return keyed;
    }

    private static PastQuestion question(Long id, Long examId, int no, QuestionType type, List<String> passages,
                                         List<String> conditions, List<String> choices) {
        PastQuestion q = PastQuestion.builder()
                .pastExamId(examId).orderNo(no).section(QuestionSection.SUBJECTIVE).no(no).type(type)
                .passageCodes(passages).stem("발문").conditions(conditions).choices(choices).points(5.0)
                .build();
        ReflectionTestUtils.setField(q, "id", id);
        return q;
    }

    private static PastExam exam(Long id, ExamType type) {
        PastExam exam = PastExam.builder()
                .workspaceId(1L).examYear(2025).semester(1).examType(type)
                .originalFilename("a.pdf").filePath("a.pdf").fileSize(1).pageCount(1).textLayer(true)
                .build();
        ReflectionTestUtils.setField(exam, "id", id);
        return exam;
    }
}
