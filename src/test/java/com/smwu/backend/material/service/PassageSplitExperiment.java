package com.smwu.backend.material.service;

import com.smwu.backend.ai.LlmResult;
import com.smwu.backend.ai.OpenAiLlmClient;
import com.smwu.backend.ai.prompt.PromptLoader;
import com.smwu.backend.material.service.PassageSplitter.SplitPassage;
import com.smwu.backend.material.service.PassageSplitter.SplitResult;
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
import java.util.stream.Stream;

/**
 * 시험범위 PDF 지문 분리 실험 (이슈 #12). 실제 OpenAI를 호출한다 (비용 발생).
 * experiments/exams/의 PDF(또는 시험범위 PDF)에서 영어 지문만 골라내는지 확인한다.
 * <pre>
 * EXPERIMENT_FILTER=압구정 ./gradlew llmTest --tests '*PassageSplitExperiment'
 * </pre>
 * 결과: build/experiments/split/{파일}.md
 */
@Tag("llm")
@EnabledIfEnvironmentVariable(named = "LLM_API_KEY", matches = ".+")
@EnabledIfEnvironmentVariable(named = "LLM_MODEL", matches = ".+")
class PassageSplitExperiment {

    private static final Path INPUT_DIR = Path.of("experiments", "exams");
    private static final Path OUTPUT_DIR = Path.of("build", "experiments", "split");

    private final JsonMapper objectMapper = JsonMapper.builder().build();

    @Test
    void PDF에서_영어_지문만_나눈다() throws IOException {
        Path pdf = pick();
        Assumptions.assumeTrue(pdf != null, "experiments/exams/에 PDF가 없어서 건너뜀");
        Files.createDirectories(OUTPUT_DIR);
        PassageSplitter splitter = new PassageSplitter(
                new OpenAiLlmClient(OpenAiLlmClient.restClientBuilder(Duration.ofSeconds(300)), objectMapper,
                        "https://api.openai.com/v1", System.getenv("LLM_API_KEY"), System.getenv("LLM_MODEL"), Duration.ofSeconds(2)),
                new PromptLoader(objectMapper));

        long started = System.currentTimeMillis();
        LlmResult<SplitResult> result = splitter.splitPdf(pdf.getFileName().toString(), Files.readAllBytes(pdf));
        long ms = System.currentTimeMillis() - started;

        StringBuilder md = new StringBuilder("# " + pdf.getFileName() + " 지문 분리 실험\n\n");
        md.append("- 지문 ").append(result.value().passages().size()).append("개, ").append(ms / 1000.0).append("초, 토큰 ")
                .append(result.inputTokens() + result.outputTokens()).append("\n\n## 경고\n\n");
        result.value().warnings().forEach(w -> md.append("- ").append(w).append('\n'));
        for (int i = 0; i < result.value().passages().size(); i++) {
            SplitPassage p = result.value().passages().get(i);
            md.append("\n## ").append(i + 1).append(". ").append(p.sourceLabel() == null ? "" : p.sourceLabel())
                    .append(p.title() == null ? "" : " · " + p.title()).append(" (").append(TextNormalizer.wordCount(p.text()))
                    .append("단어)\n\n").append(p.text()).append('\n');
        }
        Files.writeString(OUTPUT_DIR.resolve(pdf.getFileName().toString().replaceFirst("(?i)\\.pdf$", ".md")), md.toString(),
                StandardCharsets.UTF_8);
        System.out.println("지문 " + result.value().passages().size() + "개, " + ms + "ms → " + OUTPUT_DIR.toAbsolutePath());
    }

    private static Path pick() throws IOException {
        if (!Files.isDirectory(INPUT_DIR)) {
            return null;
        }
        String filter = System.getenv().getOrDefault("EXPERIMENT_FILTER", "");
        try (Stream<Path> files = Files.list(INPUT_DIR)) {
            return files.filter(p -> p.toString().toLowerCase().endsWith(".pdf"))
                    .filter(p -> filter.isBlank() || p.getFileName().toString().contains(filter.strip()))
                    .sorted().findFirst().orElse(null);
        }
    }
}
