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
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 문제 1개 생성: LLM 초안 → 코드 조립 → 코드 규칙 검증 → 실패하면 실패 이유를 알려주고 다시 생성 (최대 3번 시도).
 * 규칙 검증을 통과하면 블라인드 풀이 검증({@link BlindSolver})을 하고, 정답이 애매하면 남은 기회에 다시 생성한다.
 * 끝까지 애매하면 규칙을 통과한 마지막 문항을 NEEDS_REVIEW로 넘겨 강사가 확인하게 한다.
 * DB를 쓰지 않으므로 LLM 호출 중 트랜잭션이 열리지 않는다. 저장은 호출하는 쪽에서 한다.
 */
@Slf4j
@Component
public class ProblemGenerator {

    /** 첫 시도 + 재생성 2번 */
    public static final int MAX_ATTEMPTS = 3;

    private final LlmClient llmClient;
    private final PromptLoader promptLoader;
    private final BlindSolver blindSolver;
    private final Map<QuestionType, ProblemTypeHandler<?>> handlers = new EnumMap<>(QuestionType.class);

    public ProblemGenerator(LlmClient llmClient, PromptLoader promptLoader, BlindSolver blindSolver,
                            List<ProblemTypeHandler<?>> handlers) {
        this.llmClient = llmClient;
        this.promptLoader = promptLoader;
        this.blindSolver = blindSolver;
        handlers.forEach(h -> this.handlers.put(h.type(), h));
    }

    /**
     * @param problem         마지막 시도에서 조립된 문제 (조립조차 못 했으면 null)
     * @param status          규칙·블라인드 풀이 모두 통과 PASSED, 규칙 통과 + 풀이 불일치 NEEDS_REVIEW, 규칙 실패 FAILED
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
        return generate(context, passage, type, options, seed, List.of());
    }

    /**
     * @param existing 같은 지문·같은 유형으로 이미 만든 문항. 정답이 겹치지 않게 프롬프트로 알려주고, 겹치면 규칙 검증 실패로 다시 만든다
     */
    public Outcome generate(GenerationContext context, PassageSource passage, QuestionType type, ProblemOptions options,
                            long seed, List<AssembledProblem> existing) {
        ProblemTypeHandler<?> handler = handlers.get(type);
        if (handler == null) {
            throw new IllegalArgumentException("아직 생성할 수 없는 유형입니다: " + type);
        }
        return run(handler, context, passage, options.withDefaults(), seed, existing);
    }

    private <D> Outcome run(ProblemTypeHandler<D> handler, GenerationContext context, PassageSource passage,
                            ProblemOptions options, long seed, List<AssembledProblem> existing) {
        List<String> failures = new ArrayList<>();
        AssembledProblem last = null;
        List<ValidationCheck> lastChecks = List.of();
        String model = null;
        AssembledProblem reviewCandidate = null;
        List<ValidationCheck> reviewChecks = List.of();

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            LlmResult<D> result;
            try {
                result = llmClient.generate(request(handler, context, passage, options, failures, existing), handler.draftType());
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
            checks.add(distinctCheck(assembled, existing));
            last = assembled;
            lastChecks = checks;

            List<String> failed = checks.stream().filter(c -> !c.passed()).map(ValidationCheck::detail).toList();
            if (!failed.isEmpty()) {
                failures.add(String.join(" / ", failed));
                log.info("문제 생성 {} attempt={} 규칙 검증 실패: {}", handler.type(), attempt, failed);
                continue;
            }

            List<ValidationCheck> blind = blindSolver.verify(assembled, passage, options);
            checks.addAll(blind);
            List<String> blindFailed = blind.stream().filter(c -> !c.passed()).map(ValidationCheck::detail).toList();
            if (blindFailed.isEmpty()) {
                log.info("문제 생성 {} PASSED attempt={}", handler.type(), attempt);
                return new Outcome(assembled, ValidationStatus.PASSED, checks, attempt, failures, model);
            }
            // 규칙은 통과했지만 정답이 애매함 → 남은 기회에 고쳐 보고, 끝까지 안 되면 이 문항을 NEEDS_REVIEW로 넘긴다
            reviewCandidate = assembled;
            reviewChecks = checks;
            failures.add(String.join(" / ", blindFailed)
                    + " → 정답이 하나만 되도록 빈칸 단어를 바꾸거나, 다른 후보가 맞지 않게 요약문 문맥을 더 구체적으로 쓸 것");
            log.info("문제 생성 {} attempt={} 블라인드 풀이 불일치: {}", handler.type(), attempt, blindFailed);
        }
        if (reviewCandidate != null) {
            return new Outcome(reviewCandidate, ValidationStatus.NEEDS_REVIEW, reviewChecks, MAX_ATTEMPTS, failures, model);
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

    /** 같은 지문·같은 유형으로 이미 만든 문항과 정답이 같으면 실패 (한 지문에 어구 배열 2문항이 같은 문장으로 나오는 문제) */
    static ValidationCheck distinctCheck(AssembledProblem problem, List<AssembledProblem> existing) {
        String answer = answerKey(problem);
        boolean duplicated = answer != null && existing.stream().map(ProblemGenerator::answerKey).anyMatch(answer::equals);
        return ValidationCheck.of("DISTINCT_FROM_EXISTING", !duplicated,
                "같은 지문으로 이미 만든 문항과 정답이 같다. 이미 만든 문항에 쓰지 않은 다른 문장(또는 다른 단어)을 골라야 한다.");
    }

    private static String answerKey(AssembledProblem problem) {
        return problem == null || problem.answerText() == null ? null
                : problem.answerText().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").strip();
    }

    private <D> LlmRequest request(ProblemTypeHandler<D> handler, GenerationContext context, PassageSource passage,
                                   ProblemOptions options, List<String> failures, List<AssembledProblem> existing) {
        String avoid = existing.isEmpty() ? "" : "\n## 이 지문으로 이미 만든 같은 유형 문항의 정답 (겹치지 않게 다른 문장·단어를 고를 것)\n"
                + existing.stream().map(e -> "- " + e.answerText()).collect(Collectors.joining("\n"));
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
                        "retryFeedback", avoid + retry)),
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
