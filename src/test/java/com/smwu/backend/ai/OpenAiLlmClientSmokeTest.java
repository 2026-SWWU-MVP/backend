package com.smwu.backend.ai;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 실제 OpenAI API를 호출하는 확인용 테스트. LLM_API_KEY와 LLM_MODEL 환경변수가 있을 때만 실행된다 (비용 발생).
 * <pre>
 * ./gradlew test --tests '*OpenAiLlmClientSmokeTest' -i   (.env에 LLM_API_KEY, LLM_MODEL 설정)
 * </pre>
 * 보낸 PDF와 받은 결과는 {@code build/smoke/}에 저장된다 (input.pdf, result.json).
 */
@EnabledIfEnvironmentVariable(named = "LLM_API_KEY", matches = ".+")
@EnabledIfEnvironmentVariable(named = "LLM_MODEL", matches = ".+")
class OpenAiLlmClientSmokeTest {

    private static final Path OUTPUT_DIR = Path.of("build", "smoke");

    private final ObjectMapper objectMapper = JsonMapper.builder().build();

    record PdfSummary(String title, String lastWord, List<String> keywords) {
    }

    @Test
    void PDF를_읽고_스키마에_맞는_JSON을_돌려준다() throws IOException {
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory();
        requestFactory.setReadTimeout(Duration.ofSeconds(120));
        LlmClient client = new OpenAiLlmClient(RestClient.builder().requestFactory(requestFactory), objectMapper,
                System.getenv().getOrDefault("LLM_BASE_URL", "https://api.openai.com/v1"),
                System.getenv("LLM_API_KEY"), System.getenv("LLM_MODEL"), Duration.ofSeconds(2));

        LlmRequest request = LlmRequest.of(
                        "smoke-pdf-summary",
                        "You read English exam passages. Answer only with JSON that matches the schema.",
                        "첨부한 PDF의 제목, 본문 마지막 단어(마침표 제외), 핵심 단어 3개를 추출하시오.",
                        objectMapper.readTree("""
                                {"type":"object","additionalProperties":false,
                                 "required":["title","lastWord","keywords"],
                                 "properties":{
                                   "title":{"type":"string"},
                                   "lastWord":{"type":"string"},
                                   "keywords":{"type":"array","items":{"type":"string"}}}}
                                """))
                .withFiles(List.of(LlmFile.pdf("passage.pdf", samplePdf())));

        LlmResult<PdfSummary> result = client.generate(request, PdfSummary.class);

        System.out.println("model=" + result.model() + " tokens(in=" + result.inputTokens()
                + ", out=" + result.outputTokens() + ")\n" + result.rawText());
        saveOutput(request, result);
        assertThat(result.value().title()).containsIgnoringCase("Old Cities");
        assertThat(result.value().lastWord()).isEqualToIgnoringCase("area");
        assertThat(result.value().keywords()).isNotEmpty();
    }

    /** 사람이 직접 확인할 수 있게 보낸 PDF와 받은 결과를 build/smoke/에 저장 */
    private void saveOutput(LlmRequest request, LlmResult<PdfSummary> result) throws IOException {
        Files.createDirectories(OUTPUT_DIR);
        Files.write(OUTPUT_DIR.resolve("input.pdf"), request.files().get(0).content());

        var output = objectMapper.createObjectNode();
        output.put("model", result.model());
        output.put("inputTokens", result.inputTokens());
        output.put("outputTokens", result.outputTokens());
        output.put("prompt", request.userPrompt());
        output.set("result", objectMapper.readTree(result.rawText()));
        Files.writeString(OUTPUT_DIR.resolve("result.json"),
                objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(output), StandardCharsets.UTF_8);
    }

    /** 출력예시 지문 일부로 만든 1페이지 PDF */
    private static byte[] samplePdf() throws IOException {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
                stream.beginText();
                stream.setLeading(16);
                stream.newLineAtOffset(60, 720);
                stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD), 14);
                stream.showText("Bringing New Life to Old Cities");
                stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 11);
                for (String line : List.of("",
                        "As cities age, neighborhoods can become old and lifeless,",
                        "which may cause citizens to move away.",
                        "",
                        "A collaboration between the local government and citizens",
                        "is an effective way to revitalize the area.")) {
                    stream.newLine();
                    stream.showText(line);
                }
                stream.endText();
            }
            document.save(out);
            return out.toByteArray();
        }
    }
}
