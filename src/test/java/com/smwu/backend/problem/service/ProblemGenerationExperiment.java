package com.smwu.backend.problem.service;

import com.smwu.backend.ai.LlmClient;
import com.smwu.backend.ai.OpenAiLlmClient;
import com.smwu.backend.ai.prompt.PromptLoader;
import com.smwu.backend.pastexam.domain.ExamType;
import com.smwu.backend.pastexam.domain.PastExam;
import com.smwu.backend.pastexam.domain.PastPassage;
import com.smwu.backend.pastexam.domain.PastQuestion;
import com.smwu.backend.pastexam.extraction.ExtractedExam;
import com.smwu.backend.pastexam.extraction.ExtractedExam.QuestionSection;
import com.smwu.backend.pastexam.extraction.QuestionType;
import com.smwu.backend.problem.domain.ValidationStatus;
import com.smwu.backend.problem.type.AssembledProblem;
import com.smwu.backend.problem.type.PassageSource;
import com.smwu.backend.problem.type.ProblemOptions;
import com.smwu.backend.problem.type.SentenceOrderHandler;
import com.smwu.backend.problem.type.SummaryBlankHandler;
import com.smwu.backend.problem.type.TextNormalizer;
import com.smwu.backend.profile.domain.ProfileRule;
import com.smwu.backend.profile.domain.ProfileStats;
import com.smwu.backend.profile.service.ProfileStatsCalculator;
import com.smwu.backend.profile.service.RuleSummarizer;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 문제 생성 실험 (이슈 #15). 실제 OpenAI를 호출한다 (비용 발생).
 * 저장된 기출 추출 결과로 학교 규칙을 만들고, 그 시험지의 지문(시험범위 지문 대신)으로 요약문 빈칸·어구 배열을 만든다.
 * <pre>
 * EXPERIMENT_FILTER=압구정 ./gradlew llmTest --tests '*ProblemGenerationExperiment'
 * </pre>
 * 결과: build/experiments/generate/{시험지}.md
 */
@Tag("llm")
@EnabledIfEnvironmentVariable(named = "LLM_API_KEY", matches = ".+")
@EnabledIfEnvironmentVariable(named = "LLM_MODEL", matches = ".+")
class ProblemGenerationExperiment {

    private static final Path INPUT_DIR = Path.of("build", "experiments", "extract");
    private static final Path OUTPUT_DIR = Path.of("build", "experiments", "generate");
    private static final int PASSAGES = 4;

    private final JsonMapper objectMapper = JsonMapper.builder().build();

    @Test
    void 실제_지문으로_두_유형_문제를_만든다() throws IOException {
        Path json = pickJson();
        Assumptions.assumeTrue(json != null, "build/experiments/extract/*.json이 없어서 건너뜀");
        String name = json.getFileName().toString().replaceFirst("\\.json$", "");
        Files.createDirectories(OUTPUT_DIR);

        LlmClient llm = new OpenAiLlmClient(OpenAiLlmClient.restClientBuilder(Duration.ofSeconds(180)), objectMapper,
                "https://api.openai.com/v1", System.getenv("LLM_API_KEY"), System.getenv("LLM_MODEL"), Duration.ofSeconds(2));
        PromptLoader prompts = new PromptLoader(objectMapper);

        ExtractedExam extracted = objectMapper.treeToValue(
                objectMapper.readTree(Files.readString(json, StandardCharsets.UTF_8)).path("result"), ExtractedExam.class);
        List<PastQuestion> questions = toQuestions(extracted);
        PastExam exam = PastExam.builder().workspaceId(1L).examYear(2025).semester(1).examType(ExamType.MIDTERM)
                .originalFilename(name).filePath("x").fileSize(1).pageCount(1).textLayer(true).build();
        ReflectionTestUtils.setField(exam, "id", 1L);
        List<PastPassage> pastPassages = extracted.passages().stream().map(p -> new PastPassage(1L, p.id(), 0, p.title(), p.text())).toList();
        ProfileStats stats = ProfileStatsCalculator.calculate(1, questions);
        RuleSummarizer.Result rules = new RuleSummarizer(llm, prompts).summarize("기출 1개", stats, List.of(exam),
                questions.stream().filter(q -> q.getSection() == QuestionSection.SUBJECTIVE).toList(), Map.of(1L, pastPassages));
        Map<Long, PastQuestion> byId = questions.stream().collect(Collectors.toMap(PastQuestion::getId, q -> q));
        GenerationContext context = new GenerationContext(1L, List.of(), List.of(),
                rules.rules().stream().map(ProfileRule::text).toList(),
                rules.exampleQuestionIds().stream().map(byId::get)
                        .map(q -> new GenerationContext.Example(q.getType().getLabel(), GenerationContextFactory.describe(q))).toList());

        // 시험범위 지문 대신: 이 시험지의 긴 지문 (밑줄 대괄호 표기 제거)
        List<PassageSource> passages = extracted.passages().stream()
                .map(p -> new PassageSource(null, p.title(), TextNormalizer.normalize(p.text())))
                .filter(p -> TextNormalizer.wordCount(p.text()) >= 90)
                .sorted(Comparator.comparingInt((PassageSource p) -> TextNormalizer.wordCount(p.text())).reversed())
                .limit(PASSAGES)
                .toList();

        ProblemGenerator generator = new ProblemGenerator(llm, prompts, new BlindSolver(llm, prompts),
                List.of(new SummaryBlankHandler(), new SentenceOrderHandler()));
        StringBuilder md = new StringBuilder("# " + name + " 문제 생성 실험\n\n");
        md.append("학교 규칙 ").append(rules.rules().size()).append("개, 대표 문항 ").append(context.examples().size()).append("개 사용\n\n");
        int total = 0;
        int firstTry = 0;
        int retried = 0;
        int failed = 0;
        int needsReview = 0;
        for (int i = 0; i < passages.size(); i++) {
            PassageSource passage = passages.get(i);
            md.append("---\n\n## 지문 ").append(i + 1).append(" (").append(TextNormalizer.wordCount(passage.text())).append("단어)\n\n> ")
                    .append(passage.text()).append("\n\n");
            List<Object[]> jobs = List.of(
                    new Object[]{QuestionType.SUMMARY_BLANK, new ProblemOptions(2, i % 2 == 1, null)},
                    new Object[]{QuestionType.SENTENCE_ORDER, ProblemOptions.defaults()});
            for (Object[] job : jobs) {
                long started = System.currentTimeMillis();
                ProblemGenerator.Outcome outcome = generator.generate(context, passage, (QuestionType) job[0], (ProblemOptions) job[1], i);
                long ms = System.currentTimeMillis() - started;
                total++;
                if (outcome.status() == ValidationStatus.FAILED) {
                    failed++;
                } else if (outcome.status() == ValidationStatus.NEEDS_REVIEW) {
                    needsReview++;
                } else if (outcome.attempts() == 1) {
                    firstTry++;
                } else {
                    retried++;
                }
                md.append(render((QuestionType) job[0], outcome, ms));
                System.out.println(job[0] + " 지문" + (i + 1) + ": " + outcome.status() + " (시도 " + outcome.attempts() + ", " + ms + "ms)");
            }
        }
        md.insert(md.indexOf("\n\n") + 2, "| 생성 | 첫 시도 통과 | 재생성 후 통과 | 확인 필요(블라인드 풀이) | 실패 |\n"
                + "|---|---|---|---|---|\n| " + total + " | " + firstTry + " | " + retried + " | " + needsReview + " | " + failed + " |\n\n");
        Files.writeString(OUTPUT_DIR.resolve(name + ".md"), md.toString(), StandardCharsets.UTF_8);
        System.out.println("결과: " + OUTPUT_DIR.toAbsolutePath());
    }

    private static String render(QuestionType type, ProblemGenerator.Outcome outcome, long ms) {
        StringBuilder md = new StringBuilder("### ").append(type.getLabel()).append(" · ").append(outcome.status())
                .append(" · 시도 ").append(outcome.attempts()).append("회 · ").append(ms / 1000.0).append("초\n\n");
        AssembledProblem p = outcome.problem();
        if (p != null) {
            md.append("**").append(p.stem()).append("**\n\n[조건]\n");
            p.conditions().forEach(c -> md.append("- ").append(c).append('\n'));
            if (p.body() != null) {
                md.append("\n> ").append(p.body()).append('\n');
            }
            if (!p.choices().isEmpty()) {
                md.append("\n[보기] ").append(String.join(" / ", p.choices())).append('\n');
            }
            md.append("\n정답: ").append(p.answerText()).append("  \n해설: ").append(p.explanation()).append("\n\n");
        }
        List<String> blindIssues = outcome.checks().stream()
                .filter(c -> !c.passed() && (c.name().equals("BLIND_SOLVE") || c.name().equals("UNIQUE_ANSWER")))
                .map(c -> c.detail())
                .toList();
        if (!blindIssues.isEmpty()) {
            md.append("블라인드 풀이:\n");
            blindIssues.forEach(d -> md.append("- ").append(d).append('\n'));
            md.append('\n');
        }
        if (!outcome.attemptFailures().isEmpty()) {
            md.append("재생성 이유:\n");
            outcome.attemptFailures().forEach(f -> md.append("- ").append(f).append('\n'));
            md.append('\n');
        }
        return md.toString();
    }

    private static List<PastQuestion> toQuestions(ExtractedExam extracted) {
        List<PastQuestion> questions = new ArrayList<>();
        for (int i = 0; i < extracted.questions().size(); i++) {
            ExtractedExam.Question q = extracted.questions().get(i);
            PastQuestion pq = PastQuestion.builder().pastExamId(1L).orderNo(i + 1).section(q.section()).no(q.no())
                    .type(q.type()).passageCodes(q.passageIds()).stem(q.stem()).body(q.body()).conditions(q.conditions())
                    .choices(q.choices()).answer(q.answer()).points(q.points()).build();
            ReflectionTestUtils.setField(pq, "id", (long) (i + 1));
            questions.add(pq);
        }
        return questions;
    }

    private static Path pickJson() throws IOException {
        if (!Files.isDirectory(INPUT_DIR)) {
            return null;
        }
        String filter = System.getenv().getOrDefault("EXPERIMENT_FILTER", "");
        try (Stream<Path> files = Files.list(INPUT_DIR)) {
            return files.filter(p -> p.toString().endsWith(".json"))
                    .filter(p -> filter.isBlank() || p.getFileName().toString().contains(filter.strip()))
                    .sorted().findFirst().orElse(null);
        }
    }
}
