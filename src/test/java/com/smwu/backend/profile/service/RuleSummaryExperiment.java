package com.smwu.backend.profile.service;

import com.smwu.backend.ai.OpenAiLlmClient;
import com.smwu.backend.ai.prompt.PromptLoader;
import com.smwu.backend.pastexam.domain.ExamType;
import com.smwu.backend.pastexam.domain.PastExam;
import com.smwu.backend.pastexam.domain.PastPassage;
import com.smwu.backend.pastexam.domain.PastQuestion;
import com.smwu.backend.pastexam.extraction.ExtractedExam;
import com.smwu.backend.pastexam.extraction.ExtractedExam.QuestionSection;
import com.smwu.backend.profile.domain.ProfileRule;
import com.smwu.backend.profile.domain.ProfileStats;
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
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 출제 규칙 요약 실험 (이슈 #11). 실제 OpenAI를 호출한다 (비용 발생).
 * 기출 추출 실험(#5)이 저장한 build/experiments/extract/*.json을 시험지별로 읽어 프로필 통계·규칙을 만든다.
 * <pre>
 * ./gradlew llmTest --tests '*RuleSummaryExperiment'
 * </pre>
 * 결과: build/experiments/profile/{시험지}.md
 */
@Tag("llm")
@EnabledIfEnvironmentVariable(named = "LLM_API_KEY", matches = ".+")
@EnabledIfEnvironmentVariable(named = "LLM_MODEL", matches = ".+")
class RuleSummaryExperiment {

    private static final Path INPUT_DIR = Path.of("build", "experiments", "extract");
    private static final Path OUTPUT_DIR = Path.of("build", "experiments", "profile");

    private final JsonMapper objectMapper = JsonMapper.builder().build();

    @Test
    void 저장된_추출_결과로_학교별_출제_규칙을_요약한다() throws IOException {
        List<Path> saved = Files.isDirectory(INPUT_DIR)
                ? listJson() : List.of();
        Assumptions.assumeFalse(saved.isEmpty(), "build/experiments/extract/*.json이 없어서 건너뜀 (먼저 ExtractionExperiment 실행)");
        Files.createDirectories(OUTPUT_DIR);

        RuleSummarizer summarizer = new RuleSummarizer(
                new OpenAiLlmClient(OpenAiLlmClient.restClientBuilder(Duration.ofSeconds(180)), objectMapper,
                        "https://api.openai.com/v1", System.getenv("LLM_API_KEY"), System.getenv("LLM_MODEL"), Duration.ofSeconds(2)),
                new PromptLoader(objectMapper));

        long examId = 0;
        long questionId = 0;
        for (Path json : saved) {
            String name = json.getFileName().toString().replaceFirst("\\.json$", "");
            ExtractedExam extracted = objectMapper.treeToValue(
                    objectMapper.readTree(Files.readString(json, StandardCharsets.UTF_8)).path("result"), ExtractedExam.class);

            PastExam exam = PastExam.builder().workspaceId(1L).examYear(2025).semester(1).examType(ExamType.MIDTERM)
                    .originalFilename(name + ".pdf").filePath("x").fileSize(1).pageCount(1).textLayer(true).build();
            ReflectionTestUtils.setField(exam, "id", ++examId);
            List<PastQuestion> questions = new ArrayList<>();
            for (int i = 0; i < extracted.questions().size(); i++) {
                ExtractedExam.Question q = extracted.questions().get(i);
                PastQuestion pq = PastQuestion.builder().pastExamId(examId).orderNo(i + 1).section(q.section()).no(q.no())
                        .type(q.type()).passageCodes(q.passageIds()).stem(q.stem()).body(q.body()).conditions(q.conditions())
                        .choices(q.choices()).answer(q.answer()).points(q.points()).build();
                ReflectionTestUtils.setField(pq, "id", ++questionId);
                questions.add(pq);
            }
            long currentExamId = examId;
            List<PastPassage> passages = extracted.passages().stream()
                    .map(p -> new PastPassage(currentExamId, p.id(), 0, p.title(), p.text())).toList();

            ProfileStats stats = ProfileStatsCalculator.calculate(1, questions);
            List<PastQuestion> subjective = questions.stream().filter(q -> q.getSection() == QuestionSection.SUBJECTIVE).toList();
            System.out.println("\n=== " + name + " (서답형 " + subjective.size() + "문항) 규칙 요약 중...");
            long startedAt = System.currentTimeMillis();
            RuleSummarizer.Result result = summarizer.summarize("기출 시험지 1개 (" + name + ")", stats, List.of(exam),
                    subjective, Map.of(currentExamId, passages));
            long elapsed = System.currentTimeMillis() - startedAt;

            Files.writeString(OUTPUT_DIR.resolve(name + ".md"),
                    report(name, stats, ProfileStatsCalculator.typeMixPerPassage(questions), result, questions, elapsed),
                    StandardCharsets.UTF_8);
            System.out.println("완료: 규칙 " + result.rules().size() + "개, 대표 문항 " + result.exampleQuestionIds().size() + "개, " + elapsed + "ms");
        }
        System.out.println("\n결과: " + OUTPUT_DIR.toAbsolutePath());
    }

    private static String report(String name, ProfileStats stats, Map<?, Integer> mix, RuleSummarizer.Result result,
                                 List<PastQuestion> questions, long elapsed) {
        Map<Long, PastQuestion> byId = questions.stream().collect(Collectors.toMap(PastQuestion::getId, q -> q));
        StringBuilder md = new StringBuilder("# " + name + " 출제 프로필 (실험)\n\n");
        md.append("- 모델: ").append(result.model()).append(", 시간: ").append(elapsed / 1000.0).append("초\n\n");
        md.append("## 통계 (코드 집계)\n\n```\n").append(RuleSummarizer.describeStats(stats)).append("\n```\n\n");
        md.append("지문당 유형 구성: ").append(mix).append("\n\n");
        md.append("## 출제 규칙 (LLM 요약 + 근거 검증)\n\n");
        for (ProfileRule rule : result.rules()) {
            md.append("- **[").append(rule.category()).append("]** ").append(rule.text()).append("  \n  근거: ")
                    .append(rule.evidenceQuestionIds().stream().map(id -> label(byId.get(id))).collect(Collectors.joining(", ")))
                    .append("\n");
        }
        md.append("\n## 대표 문항\n\n");
        for (Long id : result.exampleQuestionIds()) {
            PastQuestion q = byId.get(id);
            md.append("- ").append(label(q)).append(" · ").append(q.getType()).append(": ").append(q.getStem()).append("\n");
        }
        return md.toString();
    }

    private static String label(PastQuestion q) {
        return (q.getSection() == QuestionSection.SUBJECTIVE ? "서답형 " : "") + q.getNo() + "번";
    }

    private static List<Path> listJson() throws IOException {
        try (Stream<Path> files = Files.list(INPUT_DIR)) {
            return files.filter(p -> p.toString().endsWith(".json")).sorted().toList();
        }
    }
}
