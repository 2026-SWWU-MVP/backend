package com.smwu.backend.schooldb;

import com.smwu.backend.ai.OpenAiLlmClient;
import com.smwu.backend.ai.prompt.PromptLoader;
import com.smwu.backend.pastexam.domain.ExamType;
import com.smwu.backend.pastexam.domain.PastQuestion;
import com.smwu.backend.pastexam.extraction.ExtractedExam;
import com.smwu.backend.schooldb.domain.ExamRoundStats;
import com.smwu.backend.schooldb.domain.SchoolExam;
import com.smwu.backend.schooldb.service.SchoolTrendCalculator;
import com.smwu.backend.schooldb.service.SchoolTrendCalculator.Trend;
import com.smwu.backend.schooldb.service.SchoolTrendSummarizer;
import com.smwu.backend.schooldb.service.SchoolTrendSummarizer.Summary;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 학교 경향 요약 실험 (이슈 #40). 실제 OpenAI를 호출한다 (비용 적음, 시험지당 수 초).
 * 기출 추출 실험 결과(build/experiments/extract/*.json)를 학교별 1회분 회차로 만들어 요약하고,
 * 회차가 여러 개일 때를 보려고 세 시험지를 한 학교의 2024~2025년 회차로 가정한 합성 사례도 요약한다.
 * <pre>
 * ./gradlew llmTest --tests '*SchoolTrendSummaryExperiment'
 * </pre>
 * 결과: build/experiments/trend/RESULTS.md
 */
@Tag("llm")
@EnabledIfEnvironmentVariable(named = "LLM_API_KEY", matches = ".+")
@EnabledIfEnvironmentVariable(named = "LLM_MODEL", matches = ".+")
class SchoolTrendSummaryExperiment {

    private static final Path INPUT_DIR = Path.of("build", "experiments", "extract");
    private static final Path OUTPUT_DIR = Path.of("build", "experiments", "trend");

    private final JsonMapper objectMapper = JsonMapper.builder().build();

    @Test
    void 학교_경향_요약() throws IOException {
        List<Path> files = jsonFiles();
        Assumptions.assumeTrue(!files.isEmpty(), "build/experiments/extract/*.json이 없어서 건너뜀");
        Files.createDirectories(OUTPUT_DIR);
        SchoolTrendSummarizer summarizer = new SchoolTrendSummarizer(
                new OpenAiLlmClient(OpenAiLlmClient.restClientBuilder(Duration.ofSeconds(120)), objectMapper,
                        "https://api.openai.com/v1", System.getenv("LLM_API_KEY"), System.getenv("LLM_MODEL"), Duration.ofSeconds(2)),
                new PromptLoader(objectMapper));

        Map<String, ExamRoundStats> statsByExam = new LinkedHashMap<>();
        for (Path file : files) {
            statsByExam.put(file.getFileName().toString().replaceFirst("\\.json$", ""), stats(file));
        }

        StringBuilder md = new StringBuilder("# 학교 경향 요약 실험 (#40)\n\n입력은 코드 집계 통계만 (원문 없음). 검사: 영어 6단어 이상 연속, 길이.\n");
        long id = 1;
        for (Map.Entry<String, ExamRoundStats> e : statsByExam.entrySet()) {
            SchoolExam round = round(id++, 2025, 1, ExamType.MIDTERM, e.getValue());
            md.append(run(summarizer, e.getKey(), List.of(round)));
        }
        // 합성: 세 시험지를 한 학교의 2024 2학기 기말 → 2025 1학기 중간 → 2025 2학기 중간으로 가정
        List<SchoolExam> synthetic = new ArrayList<>();
        int[][] when = {{2024, 2, 1}, {2025, 1, 0}, {2025, 2, 0}};
        int i = 0;
        for (ExamRoundStats stats : statsByExam.values()) {
            if (i == when.length) {
                break;
            }
            synthetic.add(round(id++, when[i][0], when[i][1], when[i][2] == 1 ? ExamType.FINAL : ExamType.MIDTERM, stats));
            i++;
        }
        md.append(run(summarizer, "합성 사례: 시험지 " + synthetic.size() + "개를 한 학교의 연속 회차로 가정", synthetic));

        Files.writeString(OUTPUT_DIR.resolve("RESULTS.md"), md.toString(), StandardCharsets.UTF_8);
        System.out.println("결과: " + OUTPUT_DIR.toAbsolutePath());
    }

    private static String run(SchoolTrendSummarizer summarizer, String name, List<SchoolExam> rounds) {
        Trend trend = SchoolTrendCalculator.calculate(rounds);
        String basis = "학교 DB 기출 %d회분 · 학원 1곳 기준".formatted(trend.examCount());
        long started = System.currentTimeMillis();
        Summary summary = summarizer.summarize(name + " 1학년", basis, trend, rounds, Map.of());
        long ms = System.currentTimeMillis() - started;
        StringBuilder md = new StringBuilder("\n---\n\n## ").append(name).append("\n\n")
                .append("- ").append(basis).append(", 시도 ").append(summary.attempts()).append("회, ").append(ms / 1000.0).append("초\n");
        md.append("- 코드 하이라이트: ").append(trend.highlights().isEmpty() ? "(없음)" : String.join(" / ", trend.highlights())).append("\n\n");
        md.append("**").append(summary.headline()).append("**\n\n");
        summary.points().forEach(p -> md.append("- ").append(p).append('\n'));
        md.append("\n대비 포인트\n\n");
        summary.prepTips().forEach(p -> md.append("- ").append(p).append('\n'));
        System.out.println(name + ": 시도 " + summary.attempts() + ", " + ms + "ms");
        return md.toString();
    }

    private ExamRoundStats stats(Path json) throws IOException {
        ExtractedExam extracted = objectMapper.treeToValue(
                objectMapper.readTree(Files.readString(json, StandardCharsets.UTF_8)).path("result"), ExtractedExam.class);
        List<PastQuestion> questions = new ArrayList<>();
        for (int i = 0; i < extracted.questions().size(); i++) {
            ExtractedExam.Question q = extracted.questions().get(i);
            questions.add(PastQuestion.builder().pastExamId(1L).orderNo(i + 1).section(q.section()).no(q.no())
                    .type(q.type()).passageCodes(q.passageIds()).stem(q.stem()).body(q.body()).conditions(q.conditions())
                    .choices(q.choices()).answer(q.answer()).points(q.points()).build());
        }
        return ExamRoundStats.of(questions, extracted.passages().size());
    }

    private static SchoolExam round(long id, int year, int semester, ExamType type, ExamRoundStats stats) {
        SchoolExam round = new SchoolExam(1L, 1, year, semester, type);
        ReflectionTestUtils.setField(round, "id", id);
        ReflectionTestUtils.setField(round, "stats", stats);
        return round;
    }

    private static List<Path> jsonFiles() throws IOException {
        if (!Files.isDirectory(INPUT_DIR)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(INPUT_DIR)) {
            return files.filter(p -> p.toString().endsWith(".json")).sorted().toList();
        }
    }
}
