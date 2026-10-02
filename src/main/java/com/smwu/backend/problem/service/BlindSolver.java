package com.smwu.backend.problem.service;

import com.smwu.backend.ai.LlmClient;
import com.smwu.backend.ai.LlmException;
import com.smwu.backend.ai.LlmRequest;
import com.smwu.backend.ai.LlmResult;
import com.smwu.backend.ai.prompt.PromptLoader;
import com.smwu.backend.pastexam.extraction.QuestionType;
import com.smwu.backend.problem.type.AssembledProblem;
import com.smwu.backend.problem.type.PassageSource;
import com.smwu.backend.problem.type.ProblemOptions;
import com.smwu.backend.problem.type.TextNormalizer;
import com.smwu.backend.problem.type.ValidationCheck;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 블라인드 풀이 검증: 정답을 빼고 LLM에게 문제를 풀게 해서 생성된 정답과 비교하고, 다른 정답 후보가 있는지 본다.
 * 규칙 검증을 통과한 문항에만 쓰며, 불일치하면 재생성하지 않고 NEEDS_REVIEW로 강사에게 확인을 맡긴다.
 * 어구 배열은 정답이 원문 문장 하나로 정해지고 코드로 검증하므로 대상이 아니다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BlindSolver {

    public static final String SUMMARY_BLANK_TASK = "blind-solve-summary-blank";

    private final LlmClient llmClient;
    private final PromptLoader promptLoader;

    /** LLM 응답 형식 (prompts/blind-solve-summary-blank.schema.json) */
    record SummaryBlankSolution(List<BlankSolution> blanks) {
    }

    record BlankSolution(String answer, List<String> alternatives) {
    }

    public boolean supports(QuestionType type) {
        return type == QuestionType.SUMMARY_BLANK;
    }

    /** @return 검증 항목 (대상 유형이 아니면 빈 목록) */
    public List<ValidationCheck> verify(AssembledProblem problem, PassageSource passage, ProblemOptions options) {
        if (!supports(problem.type())) {
            return List.of();
        }
        LlmRequest request = LlmRequest.of(
                SUMMARY_BLANK_TASK,
                promptLoader.text(SUMMARY_BLANK_TASK + "-system"),
                promptLoader.render(SUMMARY_BLANK_TASK + "-user", Map.of(
                        "passage", passage.text(),
                        "stem", problem.stem(),
                        "conditions", problem.conditions().stream().map(c -> "- " + c).collect(Collectors.joining("\n")),
                        "body", problem.body() == null ? "" : problem.body())),
                promptLoader.schema(SUMMARY_BLANK_TASK));
        try {
            LlmResult<SummaryBlankSolution> result = llmClient.generate(request, SummaryBlankSolution.class);
            return compareSummaryBlank(problem.answer().blanks(), result.value(), passage, options);
        } catch (LlmException e) {
            log.warn("블라인드 풀이 실패: {}", e.getDetail());
            return List.of(ValidationCheck.fail("BLIND_SOLVE", "AI 풀이 검증을 하지 못했다. 정답을 직접 확인해야 한다."));
        }
    }

    static List<ValidationCheck> compareSummaryBlank(List<String> expected, SummaryBlankSolution solution,
                                                     PassageSource passage, ProblemOptions options) {
        List<BlankSolution> solved = solution.blanks() == null ? List.of() : solution.blanks();
        if (solved.size() != expected.size()) {
            return List.of(ValidationCheck.fail("BLIND_SOLVE",
                    "AI 풀이의 빈칸 수(" + solved.size() + ")가 문제의 빈칸 수(" + expected.size() + ")와 다르다."));
        }
        List<String> mismatches = new ArrayList<>();
        List<String> ambiguous = new ArrayList<>();
        for (int i = 0; i < expected.size(); i++) {
            String answer = expected.get(i);
            BlankSolution s = solved.get(i);
            if (!same(answer, s.answer())) {
                mismatches.add("(" + (i + 1) + ") 정답 " + answer + " / AI 풀이 " + s.answer());
            }
            List<String> others = (s.alternatives() == null ? List.<String>of() : s.alternatives()).stream()
                    .map(String::strip)
                    .filter(a -> !same(answer, a))
                    .filter(TextNormalizer::isSingleWord)
                    .filter(a -> satisfiesConditions(answer, a, passage, options))
                    .distinct()
                    .toList();
            if (!others.isEmpty()) {
                ambiguous.add("(" + (i + 1) + ") " + answer + " 외에 " + String.join(", ", others) + "도 정답이 될 수 있다");
            }
        }
        return List.of(
                ValidationCheck.of("BLIND_SOLVE", mismatches.isEmpty(),
                        "정답 없이 푼 AI의 답이 정답과 다르다: " + String.join("; ", mismatches)),
                ValidationCheck.of("UNIQUE_ANSWER", ambiguous.isEmpty(),
                        "정답이 하나로 정해지지 않을 수 있다: " + String.join("; ", ambiguous)));
    }

    /** 다른 정답 후보가 [조건]을 실제로 만족하는지 (첫 철자 / 윗글에 같은 형태로 존재) */
    private static boolean satisfiesConditions(String answer, String alternative, PassageSource passage, ProblemOptions options) {
        if (options.firstLetterHint()) {
            return alternative.toLowerCase(Locale.ROOT).charAt(0) == answer.toLowerCase(Locale.ROOT).charAt(0);
        }
        return TextNormalizer.containsWord(passage.text(), alternative);
    }

    private static boolean same(String a, String b) {
        return a != null && b != null && a.strip().equalsIgnoreCase(b.strip());
    }
}
