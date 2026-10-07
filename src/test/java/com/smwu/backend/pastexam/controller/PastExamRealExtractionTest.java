package com.smwu.backend.pastexam.controller;

import com.jayway.jsonpath.JsonPath;
import com.smwu.backend.support.TestWorkspaces;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 실제 OpenAI로 기출 1개를 API 흐름(업로드 → 비동기 추출 → 저장 → 조회) 그대로 처리한다 (비용 발생).
 * experiments/exams/의 첫 번째 PDF를 사용하고, EXPERIMENT_FILTER로 파일을 고를 수 있다.
 * <pre>
 * EXPERIMENT_FILTER=현대 ./gradlew llmTest --tests '*PastExamRealExtractionTest'
 * </pre>
 */
@Tag("llm")
@EnabledIfEnvironmentVariable(named = "LLM_API_KEY", matches = ".+")
@EnabledIfEnvironmentVariable(named = "LLM_MODEL", matches = ".+")
@SpringBootTest(properties = {
        "app.llm.provider=openai",
        "app.llm.api-key=${LLM_API_KEY}",
        "app.llm.model=${LLM_MODEL}",
        "app.llm.base-url=https://api.openai.com/v1",
        "app.llm.timeout-seconds=300"
})
@AutoConfigureMockMvc
class PastExamRealExtractionTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TestWorkspaces workspaces;

    @Test
    void 실제_기출을_업로드하고_추출해서_저장한다() throws Exception {
        Path pdf = pickPdf();
        Assumptions.assumeTrue(pdf != null, "experiments/exams/에 PDF가 없어서 건너뜀");

        String uploaded = mockMvc.perform(multipart("/api/workspaces/{id}/past-exams", workspaces.create())
                        .file(new MockMultipartFile("file", pdf.getFileName().toString(), "application/pdf", Files.readAllBytes(pdf)))
                        .param("examYear", "2025").param("semester", "1").param("examType", "MIDTERM"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long examId = ((Number) JsonPath.read(uploaded, "$.id")).longValue();

        mockMvc.perform(post("/api/past-exams/{id}/analyze", examId)).andExpect(status().isAccepted());
        String exam = null;
        for (int i = 0; i < 120; i++) {
            exam = mockMvc.perform(get("/api/past-exams/{id}", examId)).andReturn().getResponse().getContentAsString();
            if (!"EXTRACTING".equals(JsonPath.read(exam, "$.status"))) {
                break;
            }
            Thread.sleep(3000);
        }
        System.out.println("시험지: " + exam);
        assertThat((String) JsonPath.read(exam, "$.status")).isEqualTo("EXTRACTED");

        String result = mockMvc.perform(get("/api/past-exams/{id}/questions", examId))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        List<Object> questions = JsonPath.read(result, "$.questions");
        List<Object> withAnswer = JsonPath.read(result, "$.questions[?(@.answer)]");
        System.out.println("문항 " + questions.size() + "개, 정답 있는 문항 " + withAnswer.size()
                + "개, 점검 이슈 " + JsonPath.read(result, "$.issueCount") + "개");
        System.out.println("시험지 이슈: " + JsonPath.read(result, "$.examIssues") + " / 경고: " + JsonPath.read(result, "$.warnings"));
        assertThat(questions).isNotEmpty();
    }

    private static Path pickPdf() throws IOException {
        Path dir = Path.of("experiments", "exams");
        if (!Files.isDirectory(dir)) {
            return null;
        }
        String filter = System.getenv().getOrDefault("EXPERIMENT_FILTER", "");
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(p -> p.getFileName().toString().toLowerCase().endsWith(".pdf"))
                    .filter(p -> filter.isBlank() || p.getFileName().toString().contains(filter.strip()))
                    .sorted().findFirst().orElse(null);
        }
    }
}
