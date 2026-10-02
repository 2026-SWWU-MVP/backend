package com.smwu.backend.problem.service;

import com.smwu.backend.ai.LlmClient;
import com.smwu.backend.ai.LlmException;
import com.smwu.backend.ai.LlmRequest;
import com.smwu.backend.ai.LlmResult;
import com.smwu.backend.ai.prompt.PromptLoader;
import com.smwu.backend.pastexam.extraction.QuestionType;
import com.smwu.backend.problem.domain.ValidationStatus;
import com.smwu.backend.problem.type.AssembledProblem;
import com.smwu.backend.problem.type.PassageSource;
import com.smwu.backend.problem.type.ProblemOptions;
import com.smwu.backend.problem.type.ProblemTypeHandler;
import com.smwu.backend.problem.type.TextNormalizer;
import com.smwu.backend.problem.type.ValidationCheck;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 문제 1개 생성: LLM 초안 → 코드 조립 → 코드 규칙 검증 → 실패하면 실패 이유를 알려주고 다시 생성 (최대 3번 시도).
 * DB를 쓰지 않으므로 LLM 호출 중 트랜잭션이 열리지 않는다. 저장은 호출하는 쪽에서 한다.
 */
@Slf4j
@Component
public class ProblemGenerator {

    /** 첫 시도 + 재생성 2번 */
    public static final int MAX_ATTEMPTS = 3;

    private final LlmClient llmClient;
    private final PromptLoader promptLoader;
    private final Map<QuestionType, ProblemTypeHandler<?>> handlers = new EnumMap<>(QuestionType.class);

    public ProblemGenerator(LlmClient llmClient, PromptLoader promptLoader, List<ProblemTypeHandler<?>> handlers) {
        this.llmClient = llmClient;
        this.promptLoader = promptLoader;
        handlers.forEach(h -> this.handlers.put(h.type(), h));
    }

    /**
     * @param problem         마지막 시도에서 조립된 문제 (조립조차 못 했으면 null)
     * @param status          규칙 검증을 모두 통과하면 PASSED, 아니면 FAILED
     * @param checks          마지막 시도의 검증 결과
     * @param attempts        시도 횟수
     * @param attemptFailures 시도별 실패 이유 (통과한 시도는 없음)
     * @param model           응답한 모델
     */
    public record Outcome(AssembledProblem problem, ValidationStatus status, List<ValidationCheck> checks, int attempts,
                          List<String> attemptFailures, String model) {
    }

    public boolean supports(QuestionType type) {
        return handlers.containsKey(type);
    }

    public Outcome generate(GenerationContext context, PassageSource passage, QuestionType type, ProblemOptions options,
                            long seed) {
        ProblemTypeHandler<?> handler = handlers.get(type);
        if (handler == null) {
            throw new IllegalArgumentException("아직 생성할 수 없는 유형입니다: " + type);
        }
        return run(handler, context, passage, options.withDefaults(), seed);
    }

    private <D> Outcome run(ProblemTypeHandler<D> handler, GenerationContext context, PassageSource passage,
                            ProblemOptions options, long seed) {
        List<String> failures = new ArrayList<>();
        AssembledProblem last = null;
        List<ValidationCheck> lastChecks = List.of();
        String model = null;

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            LlmResult<D> result;
            try {
                result = llmClient.generate(request(handler, context, passage, options, failures), handler.draftType());
            } catch (LlmException e) {
                failures.add("AI 호출 실패: " + e.getDetail());
                log.warn("문제 생성 {} attempt={} AI 호출 실패: {}", handler.type(), attempt, e.getDetail());
                continue;
            }
            model = result.model();

            AssembledProblem assembled;
            try {
                assembled = handler.assemble(result.value(), passage, options, seed + attempt);
            } catch (IllegalArgumentException e) {
                failures.add(e.getMessage());
                lastChecks = List.of(ValidationCheck.fail("ASSEMBLE", e.getMessage()));
                continue;
            }
            List<ValidationCheck> checks = new ArrayList<>(commonChecks(assembled, passage));
            checks.addAll(handler.validate(assembled, passage, options));
            last = assembled;
            lastChecks = checks;

            List<String> failed = checks.stream().filter(c -> !c.passed()).map(ValidationCheck::detail).toList();
            if (failed.isEmpty()) {
                log.info("문제 생성 {} 통과 attempt={}", handler.type(), attempt);
                return new Outcome(assembled, ValidationStatus.PASSED, checks, attempt, failures, model);
            }
            failures.add(String.join(" / ", failed));
            log.info("문제 생성 {} attempt={} 규칙 검증 실패: {}", handler.type(), attempt, failed);
        }
        return new Outcome(last, ValidationStatus.FAILED, lastChecks, MAX_ATTEMPTS, failures, model);
    }

    /** 모든 유형 공통: 해설·근거 존재, 근거 문장이 윗글에 실제로 있는지 */
    static List<ValidationCheck> commonChecks(AssembledProblem problem, PassageSource passage) {
        return List.of(
                ValidationCheck.of("EXPLANATION", problem.explanation() != null, "해설(explanation)이 비어 있다."),
                ValidationCheck.of("EVIDENCE_IN_PASSAGE",
                        problem.evidence() != null && TextNormalizer.containsSentence(passage.text(), problem.evidence()),
                        "근거 문장(evidence)이 윗글에 그대로 있지 않다. 윗글 문장을 글자 그대로 복사해야 한다."));
    }

    private <D> LlmRequest request(ProblemTypeHandler<D> handler, GenerationContext context, PassageSource passage,
                                   ProblemOptions options, List<String> failures) {
        String retry = failures.isEmpty() ? "" : "\n## 이전 시도에서 지켜지지 않은 점 (반드시 고칠 것)\n"
                + failures.stream().map(f -> "- " + f).collect(Collectors.joining("\n"));
        return LlmRequest.of(
                handler.promptName(),
                promptLoader.text("generate-system"),
                promptLoader.render("generate-user", Map.of(
                        "teacherInfo", teacherInfo(context),
                        "pastExamRules", bulletList(context.pastExamRules(), "(없음)"),
                        "examples", examples(context),
                        "passage", (passage.title() == null ? "" : passage.title() + "\n") + passage.text(),
                        "typeLabel", handler.type().getLabel(),
                        "typeInstructions", promptLoader.text(handler.promptName()).strip(),
                        "requirements", handler.requirements(options),
                        "retryFeedback", retry)),
                promptLoader.schema(handler.promptName()));
    }

    private static String teacherInfo(GenerationContext context) {
        List<String> lines = new ArrayList<>();
        context.teacherNotes().forEach(n -> lines.add("- 강사 메모: \"" + n + "\""));
        context.teacherRules().forEach(r -> lines.add("- " + r));
        return lines.isEmpty() ? "(없음)" : String.join("\n", lines);
    }

    private static String examples(GenerationContext context) {
        if (context.examples().isEmpty()) {
            return "(없음)";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < context.examples().size(); i++) {
            GenerationContext.Example e = context.examples().get(i);
            sb.append("[예시 ").append(i + 1).append(" · ").append(e.typeLabel()).append("]\n").append(e.text()).append("\n\n");
        }
        return sb.toString().strip();
    }

    private static String bulletList(List<String> items, String empty) {
        return items.isEmpty() ? empty : items.stream().map(i -> "- " + i).collect(Collectors.joining("\n"));
    }
}
