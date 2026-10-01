package com.smwu.backend.pastexam.extraction;

import com.smwu.backend.ai.LlmResult;
import com.smwu.backend.ai.OpenAiLlmClient;
import com.smwu.backend.ai.prompt.PromptLoader;
import com.smwu.backend.pastexam.extraction.ExtractedExam.Passage;
import com.smwu.backend.pastexam.extraction.ExtractedExam.Question;
import com.smwu.backend.pastexam.extraction.ExtractedExam.QuestionSection;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * 기출 PDF 멀티모달 추출 실험 (이슈 #5). 실제 OpenAI API를 호출한다 (비용 발생).
 * <ol>
 *   <li>{@code experiments/exams/}에 기출 PDF를 넣는다 (커밋되지 않음)</li>
 *   <li>{@code ./gradlew llmTest --tests '*ExtractionExperiment'}
 *       (일부 파일만: 환경변수 {@code EXPERIMENT_FILTER=압구정,현대} 처럼 파일 이름 일부를 쉼표로)</li>
 *   <li>{@code build/experiments/extract/}에서 결과 확인: 파일별 {@code .json}(원본 결과), {@code .md}(읽기용 리포트), {@code SUMMARY.md}</li>
 * </ol>
 * {@link ExtractionChecker}만 고쳤을 때는 API를 다시 부르지 않고 저장된 결과만 다시 점검할 수 있다:
 * {@code RECHECK=true ./gradlew llmTest --tests '*ExtractionExperiment'}
 */
@Tag("llm")
@EnabledIfEnvironmentVariable(named = "LLM_API_KEY", matches = ".+")
@EnabledIfEnvironmentVariable(named = "LLM_MODEL", matches = ".+")
class ExtractionExperiment {

    private static final Path INPUT_DIR = Path.of("experiments", "exams");
    private static final Path OUTPUT_DIR = Path.of("build", "experiments", "extract");

    private final JsonMapper objectMapper = JsonMapper.builder().build();

    @Test
    void 폴더의_기출_PDF를_모두_추출한다() throws IOException {
        Assumptions.assumeFalse(isRecheck(), "RECHECK 모드라 추출은 건너뜀");
        List<Path> pdfs = listPdfs();
        Assumptions.assumeFalse(pdfs.isEmpty(), "experiments/exams/에 PDF가 없어서 건너뜀");
        Files.createDirectories(OUTPUT_DIR);

        QuestionExtractor extractor = new QuestionExtractor(openAiClient(), new PromptLoader(objectMapper));
        List<String> summaryRows = new ArrayList<>();

        for (Path pdf : pdfs) {
            String name = pdf.getFileName().toString().replaceFirst("(?i)\\.pdf$", "");
            System.out.println("\n=== " + pdf.getFileName() + " 추출 중...");
            long startedAt = System.currentTimeMillis();
            try {
                LlmResult<ExtractedExam> result = extractor.extract(pdf.getFileName().toString(), Files.readAllBytes(pdf));
                long elapsedMs = System.currentTimeMillis() - startedAt;
                List<String> issues = ExtractionChecker.check(result.value());

                writeJson(name, result, elapsedMs, issues);
                Files.writeString(OUTPUT_DIR.resolve(name + ".md"),
                        report(pdf.getFileName().toString(), result, elapsedMs, issues), StandardCharsets.UTF_8);

                summaryRows.add(summaryRow(pdf.getFileName().toString(), result, elapsedMs, issues));
                System.out.println("완료: 문항 " + result.value().questions().size() + "개, 점검 이슈 " + issues.size() + "개");
            } catch (RuntimeException e) {
                summaryRows.add("| %s | 실패: %s | | | | | | |".formatted(pdf.getFileName(), e));
                System.out.println("실패: " + e);
            }
        }

        writeSummary(System.getenv().getOrDefault("EXPERIMENT_FILTER", "").isBlank() ? "SUMMARY.md" : "SUMMARY-partial.md",
                summaryRows);
        System.out.println("\n결과: " + OUTPUT_DIR.toAbsolutePath());
    }

    @Test
    void 저장된_추출_결과를_다시_점검한다() throws IOException {
        Assumptions.assumeTrue(isRecheck(), "RECHECK=true 일 때만 실행");
        List<Path> saved;
        try (Stream<Path> files = Files.list(OUTPUT_DIR)) {
            saved = files.filter(p -> p.toString().endsWith(".json")).sorted().toList();
        }
        List<String> summaryRows = new ArrayList<>();
        for (Path json : saved) {
            ObjectNode output = (ObjectNode) objectMapper.readTree(Files.readString(json, StandardCharsets.UTF_8));
            ExtractedExam exam = objectMapper.treeToValue(output.path("result"), ExtractedExam.class);
            List<String> issues = ExtractionChecker.check(exam);
            output.set("checkerIssues", objectMapper.valueToTree(issues));
            Files.writeString(json, objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(output), StandardCharsets.UTF_8);

            String name = json.getFileName().toString().replaceFirst("\\.json$", "");
            LlmResult<ExtractedExam> result = new LlmResult<>(exam, "", output.path("model").asString(),
                    output.path("inputTokens").asInt(), output.path("outputTokens").asInt());
            long elapsedMs = output.path("elapsedMs").asLong();
            Files.writeString(OUTPUT_DIR.resolve(name + ".md"), report(name + ".pdf", result, elapsedMs, issues), StandardCharsets.UTF_8);
            summaryRows.add(summaryRow(name + ".pdf", result, elapsedMs, issues));
            System.out.println(name + ": 점검 이슈 " + issues.size() + "개");
        }
        writeSummary("SUMMARY.md", summaryRows);
    }

    private static boolean isRecheck() {
        return "true".equalsIgnoreCase(System.getenv("RECHECK"));
    }

    private static String summaryRow(String filename, LlmResult<ExtractedExam> result, long elapsedMs, List<String> issues) {
        ExtractedExam exam = result.value();
        long subjective = exam.questions().stream().filter(q -> q.section() == QuestionSection.SUBJECTIVE).count();
        return "| %s | %d | %d | %d | %d | %d | %d | %.1fs |".formatted(filename,
                exam.passages().size(), exam.questions().size() - subjective, subjective,
                issues.size(), exam.warnings().size(), result.inputTokens() + result.outputTokens(), elapsedMs / 1000.0);
    }

    private static void writeSummary(String fileName, List<String> rows) throws IOException {
        Files.writeString(OUTPUT_DIR.resolve(fileName), """
                # 기출 추출 실험 요약

                모델: %s

                | 파일 | 지문 | 객관식 | 서술형 | 점검 이슈 | 모델 경고 | 토큰 | 시간 |
                |---|---|---|---|---|---|---|---|
                %s
                """.formatted(System.getenv("LLM_MODEL"), String.join("\n", rows)), StandardCharsets.UTF_8);
    }

    private void writeJson(String name, LlmResult<ExtractedExam> result, long elapsedMs, List<String> issues) throws IOException {
        ObjectNode output = objectMapper.createObjectNode();
        output.put("model", result.model());
        output.put("inputTokens", result.inputTokens());
        output.put("outputTokens", result.outputTokens());
        output.put("elapsedMs", elapsedMs);
        output.set("checkerIssues", objectMapper.valueToTree(issues));
        // 코드 정리(normalize)까지 반영된 결과. 모델 원문은 rawText
        output.set("result", objectMapper.valueToTree(result.value()));
        Files.writeString(OUTPUT_DIR.resolve(name + ".json"),
                objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(output), StandardCharsets.UTF_8);
    }

    private static String report(String filename, LlmResult<ExtractedExam> result, long elapsedMs, List<String> issues) {
        ExtractedExam exam = result.value();
        StringBuilder md = new StringBuilder();
        md.append("# ").append(filename).append("\n\n");
        md.append("- 모델: ").append(result.model()).append("\n");
        md.append("- 토큰: 입력 ").append(result.inputTokens()).append(" / 출력 ").append(result.outputTokens()).append("\n");
        md.append("- 시간: ").append(String.format("%.1f", elapsedMs / 1000.0)).append("초\n");
        md.append("- 지문 ").append(exam.passages().size()).append("개, 문항 ").append(exam.questions().size()).append("개\n\n");

        Map<String, Integer> typeCounts = new TreeMap<>();
        exam.questions().forEach(q -> typeCounts.merge(q.type().name(), 1, Integer::sum));
        md.append("## 유형 분포\n\n| 유형 | 개수 |\n|---|---|\n");
        typeCounts.forEach((type, count) -> md.append("| ").append(type).append(" | ").append(count).append(" |\n"));

        md.append("\n## 코드 점검 이슈 (").append(issues.size()).append(")\n\n");
        appendList(md, issues);
        md.append("\n## 모델 경고 (").append(exam.warnings().size()).append(")\n\n");
        appendList(md, exam.warnings());

        md.append("\n## 지문\n");
        for (Passage p : exam.passages()) {
            md.append("\n### ").append(p.id()).append(p.title() == null ? "" : " · " + p.title()).append("\n\n");
            md.append("> ").append(p.text().replace("\n", "\n> ")).append("\n");
        }

        md.append("\n## 문항\n");
        for (Question q : exam.questions()) {
            md.append("\n### ").append(q.section() == QuestionSection.SUBJECTIVE ? "서술형 " : "").append(q.no()).append("번 · ")
                    .append(q.type()).append(q.passageIds().isEmpty() ? "" : " · " + String.join(", ", q.passageIds()))
                    .append(q.points() == null ? "" : " · " + q.points() + "점").append("\n\n");
            md.append("**").append(q.stem()).append("**\n\n");
            if (q.body() != null) {
                md.append("> ").append(q.body().replace("\n", "\n> ")).append("\n\n");
            }
            if (!q.conditions().isEmpty()) {
                md.append("[조건]\n");
                q.conditions().forEach(c -> md.append("1. ").append(c).append("\n"));
                md.append("\n");
            }
            if (!q.choices().isEmpty()) {
                md.append(q.section() == QuestionSection.OBJECTIVE ? "선택지: " : "[보기]: ")
                        .append(String.join(" / ", q.choices())).append("\n\n");
            }
            if (q.answer() != null) {
                md.append("정답(시험지 표기): ").append(q.answer()).append("\n");
            }
        }
        return md.toString();
    }

    private static void appendList(StringBuilder md, List<String> items) {
        if (items.isEmpty()) {
            md.append("없음\n");
        }
        items.forEach(item -> md.append("- ").append(item).append("\n"));
    }

    private OpenAiLlmClient openAiClient() {
        return new OpenAiLlmClient(OpenAiLlmClient.restClientBuilder(Duration.ofSeconds(300)), objectMapper,
                System.getenv().getOrDefault("LLM_BASE_URL", "https://api.openai.com/v1"),
                System.getenv("LLM_API_KEY"), System.getenv("LLM_MODEL"), Duration.ofSeconds(2));
    }

    private static List<Path> listPdfs() throws IOException {
        if (!Files.isDirectory(INPUT_DIR)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(INPUT_DIR)) {
            String filter = System.getenv().getOrDefault("EXPERIMENT_FILTER", "");
            List<String> keywords = filter.isBlank() ? List.of() : List.of(filter.split(","));
            return files.filter(p -> p.getFileName().toString().toLowerCase().endsWith(".pdf"))
                    .filter(p -> keywords.isEmpty() || keywords.stream().anyMatch(k -> p.getFileName().toString().contains(k.strip())))
                    .sorted().toList();
        }
    }
}
