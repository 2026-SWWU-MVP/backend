package com.smwu.backend.problem.service;

import com.smwu.backend.ai.LlmClient;
import com.smwu.backend.ai.LlmException;
import com.smwu.backend.ai.LlmRequest;
import com.smwu.backend.ai.LlmResult;
import com.smwu.backend.ai.prompt.PromptLoader;
import com.smwu.backend.pastexam.extraction.QuestionType;
import com.smwu.backend.problem.domain.ValidationStatus;
import com.smwu.backend.problem.type.PassageSource;
import com.smwu.backend.problem.type.ProblemOptions;
import com.smwu.backend.problem.type.SentenceOrderHandler;
import com.smwu.backend.problem.type.SummaryBlankHandler;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ProblemGeneratorTest {

    private static final PassageSource PASSAGE = new PassageSource(1L, "Old Cities",
            "As cities age, neighborhoods can become old and lifeless, which may cause citizens to move away. "
                    + "However, having a limited budget, the government was unable to do so and had to come up with a new plan.");
    private static final String SENTENCE = "However, having a limited budget, the government was unable to do so and had to come up with a new plan.";
    private static final SentenceOrderHandler.Draft VALID_ORDER = new SentenceOrderHandler.Draft(SENTENCE,
            List.of("However", "having", "a limited budget", "the government", "was unable", "to do so", "and had to come up with", "a new plan"),
            "분사구문 설명");
    private static final SentenceOrderHandler.Draft NOT_IN_PASSAGE = new SentenceOrderHandler.Draft(
            "However, with little money, the government could not do so and had to make a brand new plan.",
            List.of("However", "with little money", "the government", "could not do so", "and had to make", "a brand new plan"), "x");

    private final List<LlmRequest> requests = new ArrayList<>();
    private final Deque<Object> responses = new ArrayDeque<>();

    private final LlmClient fakeLlm = new LlmClient() {
        @Override
        @SuppressWarnings("unchecked")
        public <T> LlmResult<T> generate(LlmRequest request, Class<T> responseType) {
            requests.add(request);
            Object next = responses.poll();
            if (next instanceof LlmException e) {
                throw e;
            }
            return new LlmResult<>((T) next, "{}", "fake-model", 0, 0);
        }
    };

    /** 블라인드 풀이용 가짜 LLM: 넣은 순서대로 풀이를 돌려주고, 마지막 풀이는 계속 돌려준다 */
    private final Deque<BlindSolver.SummaryBlankSolution> blindSolutions = new ArrayDeque<>();
    private final LlmClient blindLlm = new LlmClient() {
        @Override
        @SuppressWarnings("unchecked")
        public <T> LlmResult<T> generate(LlmRequest request, Class<T> responseType) {
            requests.add(request);
            Object next = blindSolutions.size() > 1 ? blindSolutions.poll() : blindSolutions.peek();
            return new LlmResult<>((T) next, "{}", "fake-model", 0, 0);
        }
    };

    private final PromptLoader prompts = new PromptLoader(JsonMapper.builder().build());
    private final ProblemGenerator generator = new ProblemGenerator(fakeLlm, prompts, new BlindSolver(blindLlm, prompts),
            List.of(new SummaryBlankHandler(), new SentenceOrderHandler()));

    private final GenerationContext context = new GenerationContext(9L,
            List.of("이번엔 어구 배열 위주래요"),
            List.of("이번 시험 서술형은 어구 배열 위주로 출제된다."),
            List.of("어구 배열은 분사구문이 포함된 긴 문장에서 나온다."),
            List.of(new GenerationContext.Example("어구 배열", "발문: 윗글에 나온 문장이 되도록 배열하시오.\n[보기] a / b / c")));

    @Test
    void 규칙_검증에_실패하면_이유를_알려주고_다시_생성한다() {
        responses.add(NOT_IN_PASSAGE);
        responses.add(VALID_ORDER);

        ProblemGenerator.Outcome outcome = generator.generate(context, PASSAGE, QuestionType.SENTENCE_ORDER, ProblemOptions.defaults(), 1L);

        assertThat(outcome.status()).isEqualTo(ValidationStatus.PASSED);
        assertThat(outcome.attempts()).isEqualTo(2);
        assertThat(outcome.attemptFailures()).singleElement().asString().contains("대상 문장이 윗글에 그대로 있지 않다");
        assertThat(outcome.problem().answer().sentence()).isEqualTo(SENTENCE);
        assertThat(outcome.model()).isEqualTo("fake-model");

        assertThat(requests.get(0).userPrompt()).doesNotContain("이전 시도에서 지켜지지 않은 점");
        assertThat(requests.get(1).userPrompt()).contains("이전 시도에서 지켜지지 않은 점", "대상 문장이 윗글에 그대로 있지 않다");
    }

    @Test
    void 프롬프트에_강사_정보_기출_규칙_예시_지문_유형별_지시가_들어간다() {
        responses.add(VALID_ORDER);

        generator.generate(context, PASSAGE, QuestionType.SENTENCE_ORDER, ProblemOptions.defaults(), 1L);

        LlmRequest request = requests.get(0);
        assertThat(request.task()).isEqualTo("generate-sentence-order");
        assertThat(request.systemPrompt()).contains("강사 정보를 따른다");
        assertThat(request.userPrompt())
                .contains("- 강사 메모: \"이번엔 어구 배열 위주래요\"", "- 이번 시험 서술형은 어구 배열 위주로 출제된다.")
                .contains("- 어구 배열은 분사구문이 포함된 긴 문장에서 나온다.")
                .contains("[예시 1 · 어구 배열]")
                .contains("Old Cities\nAs cities age")
                .contains("## 만들 문제: 어구 배열", "chunks: sentence를 원래 순서대로 나눈 어구 목록")
                .contains("- 대상 문장: 12단어 이상");
        assertThat(request.responseSchema().path("required").toString()).contains("sentence", "chunks");
    }

    @Test
    void 세_번_모두_실패하면_FAILED_마지막_결과와_이유를_남긴다() {
        responses.add(NOT_IN_PASSAGE);
        responses.add(new SentenceOrderHandler.Draft(SENTENCE, List.of("only", "two"), "x"));
        responses.add(NOT_IN_PASSAGE);

        ProblemGenerator.Outcome outcome = generator.generate(context, PASSAGE, QuestionType.SENTENCE_ORDER, ProblemOptions.defaults(), 1L);

        assertThat(outcome.status()).isEqualTo(ValidationStatus.FAILED);
        assertThat(outcome.attempts()).isEqualTo(3);
        assertThat(outcome.attemptFailures()).hasSize(3);
        assertThat(outcome.attemptFailures().get(1)).isEqualTo("어구는 5~10개여야 하는데 2개다.");
        assertThat(outcome.problem()).isNotNull();
        assertThat(outcome.checks()).anyMatch(c -> !c.passed() && c.name().equals("SENTENCE_IN_PASSAGE"));
    }

    @Test
    void AI_호출이_계속_실패하면_문제_없이_FAILED() {
        for (int i = 0; i < 3; i++) {
            responses.add(new LlmException("HTTP 500"));
        }

        ProblemGenerator.Outcome outcome = generator.generate(GenerationContext.empty(), PASSAGE, QuestionType.SUMMARY_BLANK,
                ProblemOptions.defaults(), 1L);

        assertThat(outcome.status()).isEqualTo(ValidationStatus.FAILED);
        assertThat(outcome.problem()).isNull();
        assertThat(outcome.attemptFailures()).containsOnly("AI 호출 실패: HTTP 500");
    }

    private static final SummaryBlankHandler.Draft BLANK_DRAFT = new SummaryBlankHandler.Draft(
            "Aging neighborhoods may turn [[lifeless]] and lose [[citizens]] over time as they decline.",
            "As cities age, neighborhoods can become old and lifeless, which may cause citizens to move away.", "해설");
    private static final BlindSolver.SummaryBlankSolution AMBIGUOUS = new BlindSolver.SummaryBlankSolution(List.of(
            new BlindSolver.BlankSolution("old", List.of()),
            new BlindSolver.BlankSolution("citizens", List.of("residents", "government"))));
    private static final BlindSolver.SummaryBlankSolution CLEAR = new BlindSolver.SummaryBlankSolution(List.of(
            new BlindSolver.BlankSolution("lifeless", List.of()),
            new BlindSolver.BlankSolution("citizens", List.of())));

    @Test
    void 블라인드_풀이가_계속_어긋나면_3번_시도_후_NEEDS_REVIEW() {
        for (int i = 0; i < 3; i++) {
            responses.add(BLANK_DRAFT);
        }
        blindSolutions.add(AMBIGUOUS);

        ProblemGenerator.Outcome outcome = generator.generate(GenerationContext.empty(), PASSAGE, QuestionType.SUMMARY_BLANK,
                ProblemOptions.defaults(), 1L);

        assertThat(outcome.status()).isEqualTo(ValidationStatus.NEEDS_REVIEW);
        assertThat(outcome.attempts()).isEqualTo(3);
        assertThat(outcome.problem()).isNotNull();
        assertThat(outcome.attemptFailures()).hasSize(3).allSatisfy(f -> assertThat(f).contains("정답이 하나만 되도록"));
        // residents는 윗글에 없어 무시되고, government는 윗글에 있으므로 다른 정답 후보로 잡힌다
        assertThat(outcome.checks()).filteredOn(c -> !c.passed()).extracting(c -> c.name())
                .containsExactly("BLIND_SOLVE", "UNIQUE_ANSWER");
        assertThat(outcome.checks()).filteredOn(c -> c.name().equals("BLIND_SOLVE")).singleElement()
                .satisfies(c -> assertThat(c.detail()).contains("(1) 정답 lifeless / AI 풀이 old"));
        // 블라인드 풀이 프롬프트에는 정답이 없다
        LlmRequest blindRequest = requests.get(requests.size() - 1);
        assertThat(blindRequest.task()).isEqualTo("blind-solve-summary-blank");
        assertThat(blindRequest.userPrompt()).contains("(1) __________", "윗글의 내용을 요약할 때").doesNotContain("lifeless and lose");
    }

    @Test
    void 블라인드_풀이로_애매함이_발견되면_다시_생성해서_고친다() {
        responses.add(BLANK_DRAFT);
        responses.add(BLANK_DRAFT);
        blindSolutions.add(AMBIGUOUS);
        blindSolutions.add(CLEAR);

        ProblemGenerator.Outcome outcome = generator.generate(GenerationContext.empty(), PASSAGE, QuestionType.SUMMARY_BLANK,
                ProblemOptions.defaults(), 1L);

        assertThat(outcome.status()).isEqualTo(ValidationStatus.PASSED);
        assertThat(outcome.attempts()).isEqualTo(2);
        assertThat(outcome.attemptFailures()).singleElement().asString().contains("government도 정답이 될 수 있다");
        // 두 번째 생성 프롬프트에 애매했던 이유가 들어간다
        assertThat(requests).filteredOn(r -> r.task().equals("generate-summary-blank")).last()
                .satisfies(r -> assertThat(r.userPrompt()).contains("이전 시도에서 지켜지지 않은 점", "government도 정답이 될 수 있다"));
    }

    @Test
    void 근거_문장이_윗글에_없으면_실패() {
        responses.add(new SummaryBlankHandler.Draft(
                "Aging neighborhoods may turn [[lifeless]] and lose [[citizens]] over time as they decline.",
                "This sentence is not in the passage.", "해설"));
        responses.add(new SummaryBlankHandler.Draft(
                "Aging neighborhoods may turn [[lifeless]] and lose [[citizens]] over time as they decline.",
                "As cities age, neighborhoods can become old and lifeless, which may cause citizens to move away.", "해설"));

        // 대소문자만 다른 답은 같은 답, 윗글에 없는 후보(residents)는 "형태 변경 없이" 조건을 만족하지 않으므로 무시
        blindSolutions.add(new BlindSolver.SummaryBlankSolution(List.of(
                new BlindSolver.BlankSolution("Lifeless", List.of("residents")),
                new BlindSolver.BlankSolution("citizens", List.of("residents")))));

        ProblemGenerator.Outcome outcome = generator.generate(GenerationContext.empty(), PASSAGE, QuestionType.SUMMARY_BLANK,
                ProblemOptions.defaults(), 1L);

        assertThat(outcome.attempts()).isEqualTo(2);
        assertThat(outcome.attemptFailures().get(0)).contains("근거 문장(evidence)이 윗글에 그대로 있지 않다");
        assertThat(outcome.status()).isEqualTo(ValidationStatus.PASSED);
    }
}
