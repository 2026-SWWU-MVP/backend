package com.smwu.backend.problem.service;

import com.smwu.backend.ai.LlmClient;
import com.smwu.backend.ai.OpenAiLlmClient;
import com.smwu.backend.ai.prompt.PromptLoader;
import com.smwu.backend.pastexam.extraction.ExtractedExam;
import com.smwu.backend.pastexam.extraction.QuestionType;
import com.smwu.backend.problem.domain.ValidationStatus;
import com.smwu.backend.problem.type.AssembledProblem;
import com.smwu.backend.problem.type.GrammarFixHandler;
import com.smwu.backend.problem.type.GuidedWritingHandler;
import com.smwu.backend.problem.type.PassageSource;
import com.smwu.backend.problem.type.ProblemOptions;
import com.smwu.backend.problem.type.SentenceOrderHandler;
import com.smwu.backend.problem.type.SummaryBlankHandler;
import com.smwu.backend.problem.type.TextNormalizer;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
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
import java.util.stream.Stream;

/**
 * 강사 검수 기록이 생성에 반영되는지 (#42). 실제 OpenAI를 호출한다 (비용 적음).
 * 같은 지문으로 어구 배열을 검수 기록 없이 / 있이 만들어, 기록이 요구한 방향(조각을 더 크게, 해설을 짧게)으로 바뀌는지 잰다.
 * <pre>
 * EXPERIMENT_FILTER=압구정 ./gradlew llmTest --tests '*TeacherReviewExperiment'
 * </pre>
 * 결과: build/experiments/teacher-review/RESULTS.md
 */
@Tag("llm")
@EnabledIfEnvironmentVariable(named = "LLM_API_KEY", matches = ".+")
@EnabledIfEnvironmentVariable(named = "LLM_MODEL", matches = ".+")
class TeacherReviewExperiment {

    private static final Path INPUT_DIR = Path.of("build", "experiments", "extract");
    private static final Path OUTPUT_DIR = Path.of("build", "experiments", "teacher-review");
    private static final int PASSAGES = 5;

    /** 강사가 이전 생성 결과를 검수하면서 남긴 기록 (TeacherPreferences가 만드는 형식 그대로) */
    static final List<String> REVIEWS = List.of(
            "폐기 (사유: [보기] 조각이 한두 단어씩 너무 잘게 나뉘어 너무 쉬움. 의미 단위로 크게 나눌 것): However, having a limited budget, ...",
            "수정: 해설 \"having a limited budget은 이유를 나타내는 분사구문으로, 주절의 주어 the government 앞에 온다. was unable to do so와 had to come up with는 and로 병렬 연결된다.\" → \"분사구문(이유) + 주절, 동사 두 개는 and로 병렬.\"",
            "수정: 해설 \"관계대명사 which가 앞 절 전체를 받는 계속적 용법이며, 주절 뒤에 콤마와 함께 온다. 따라서 which 이하가 마지막에 위치한다.\" → \"계속적 용법 which가 앞 절 전체를 받음.\"");

    private final JsonMapper objectMapper = JsonMapper.builder().build();

    private record Stat(int chunks, double wordsPerChunk, int explanationChars, ValidationStatus status) {
    }

    @Test
    void 검수_기록이_요구한_방향으로_바뀌는지() throws IOException {
        Path json = pickJson();
        Assumptions.assumeTrue(json != null, "build/experiments/extract/*.json이 없어서 건너뜀");
        Files.createDirectories(OUTPUT_DIR);
        LlmClient llm = new OpenAiLlmClient(OpenAiLlmClient.restClientBuilder(Duration.ofSeconds(180)), objectMapper,
                "https://api.openai.com/v1", System.getenv("LLM_API_KEY"), System.getenv("LLM_MODEL"), Duration.ofSeconds(2));
        PromptLoader prompts = new PromptLoader(objectMapper);
        ProblemGenerator generator = new ProblemGenerator(llm, prompts, new BlindSolver(llm, prompts),
                List.of(new SummaryBlankHandler(), new SentenceOrderHandler(), new GrammarFixHandler(), new GuidedWritingHandler()));

        ExtractedExam extracted = objectMapper.treeToValue(
                objectMapper.readTree(Files.readString(json, StandardCharsets.UTF_8)).path("result"), ExtractedExam.class);
        List<PassageSource> passages = extracted.passages().stream()
                .map(p -> new PassageSource(null, p.title(), TextNormalizer.normalize(p.text())))
                .filter(p -> TextNormalizer.wordCount(p.text()) >= 90)
                .sorted(Comparator.comparingInt((PassageSource p) -> TextNormalizer.wordCount(p.text())).reversed())
                .limit(PASSAGES)
                .toList();

        GenerationContext without = GenerationContext.empty();
        GenerationContext with = new GenerationContext(null, List.of(), List.of(), List.of(), List.of(),
                Map.of(QuestionType.SENTENCE_ORDER, REVIEWS));

        List<Stat> before = new ArrayList<>();
        List<Stat> after = new ArrayList<>();
        StringBuilder detail = new StringBuilder();
        for (int i = 0; i < passages.size(); i++) {
            Stat a = run(generator, without, passages.get(i), i, detail, "기록 없음");
            Stat b = run(generator, with, passages.get(i), i, detail, "기록 있음");
            if (a != null) {
                before.add(a);
            }
            if (b != null) {
                after.add(b);
            }
        }

        StringBuilder md = new StringBuilder("# 강사 검수 기록 반영 실험 (#42)\n\n");
        md.append("같은 지문 ").append(passages.size()).append("개로 어구 배열을 만들어 비교. 검수 기록:\n\n");
        REVIEWS.forEach(r -> md.append("- ").append(r).append('\n'));
        md.append("\n| | 문항 | 조각 수 (평균) | 조각당 단어 (평균) | 해설 글자 수 (평균) | PASSED |\n|---|---|---|---|---|---|\n");
        md.append(row("기록 없음", before)).append(row("기록 있음", after));
        md.append("\n---\n").append(detail);
        Files.writeString(OUTPUT_DIR.resolve("RESULTS.md"), md.toString(), StandardCharsets.UTF_8);
        System.out.println(row("기록 없음", before) + row("기록 있음", after));
    }

    private static Stat run(ProblemGenerator generator, GenerationContext context, PassageSource passage, int seed,
                            StringBuilder detail, String label) {
        ProblemGenerator.Outcome outcome = generator.generate(context, passage, QuestionType.SENTENCE_ORDER, ProblemOptions.defaults(), seed);
        AssembledProblem p = outcome.problem();
        if (p == null) {
            return null;
        }
        int words = p.choices().stream().mapToInt(TextNormalizer::wordCount).sum();
        Stat stat = new Stat(p.choices().size(), words / (double) p.choices().size(),
                p.explanation() == null ? 0 : p.explanation().length(), outcome.status());
        detail.append("\n### 지문 ").append(seed + 1).append(" · ").append(label).append(" · ").append(outcome.status()).append("\n\n")
                .append("[보기] ").append(String.join(" / ", p.choices())).append("\n\n해설: ").append(p.explanation()).append('\n');
        return stat;
    }

    private static String row(String label, List<Stat> stats) {
        return "| " + label + " | " + stats.size() + " | " + avg(stats.stream().mapToDouble(Stat::chunks).toArray())
                + " | " + avg(stats.stream().mapToDouble(Stat::wordsPerChunk).toArray())
                + " | " + avg(stats.stream().mapToDouble(Stat::explanationChars).toArray())
                + " | " + stats.stream().filter(s -> s.status() == ValidationStatus.PASSED).count() + " |\n";
    }

    private static String avg(double[] values) {
        return values.length == 0 ? "-" : String.format("%.1f", java.util.Arrays.stream(values).average().orElse(0));
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
