package com.smwu.backend.pastexam.extraction;

import com.smwu.backend.ai.LlmClient;
import com.smwu.backend.ai.LlmRequest;
import com.smwu.backend.ai.LlmResult;
import com.smwu.backend.ai.MockLlmClient;
import com.smwu.backend.ai.prompt.PromptLoader;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class QuestionExtractorTest {

    private final JsonMapper objectMapper = JsonMapper.builder().build();
    private final PromptLoader promptLoader = new PromptLoader(objectMapper);

    @Test
    void 프롬프트_스키마_PDF를_담아_요청한다() {
        AtomicReference<LlmRequest> captured = new AtomicReference<>();
        LlmClient fake = new LlmClient() {
            @Override
            public <T> LlmResult<T> generate(LlmRequest request, Class<T> responseType) {
                captured.set(request);
                return new MockLlmClient(objectMapper).generate(request, responseType);
            }
        };

        LlmResult<ExtractedExam> result = new QuestionExtractor(fake, promptLoader)
                .extract("2025-1-mid.pdf", new byte[]{1, 2, 3});

        LlmRequest request = captured.get();
        assertThat(request.task()).isEqualTo("extract-questions");
        assertThat(request.systemPrompt()).contains("대괄호 표기 규칙");
        assertThat(request.userPrompt()).contains("2025-1-mid.pdf");
        assertThat(request.responseSchema().path("required").toString()).contains("passages", "questions", "warnings");
        assertThat(request.files()).singleElement()
                .satisfies(f -> assertThat(f.mimeType()).isEqualTo("application/pdf"));

        // Mock 응답(resources/mock-llm/extract-questions.json)도 구조 점검을 통과해야 한다
        assertThat(result.value().questions()).isNotEmpty();
        assertThat(ExtractionChecker.check(result.value())).isEmpty();
    }
}
