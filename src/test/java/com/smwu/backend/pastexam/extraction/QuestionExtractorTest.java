package com.smwu.backend.pastexam.extraction;

import com.smwu.backend.ai.LlmClient;
import com.smwu.backend.ai.LlmFile;
import com.smwu.backend.ai.LlmRequest;
import com.smwu.backend.ai.LlmResult;
import com.smwu.backend.ai.MockLlmClient;
import com.smwu.backend.ai.prompt.PromptLoader;
import com.smwu.backend.document.TestPdfs;
import com.smwu.backend.pastexam.extraction.ExtractedExam.Question;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static com.smwu.backend.pastexam.extraction.ExtractedExam.QuestionSection.OBJECTIVE;
import static com.smwu.backend.pastexam.extraction.ExtractedExam.QuestionSection.SUBJECTIVE;
import static org.assertj.core.api.Assertions.assertThat;

class QuestionExtractorTest {

    private final JsonMapper objectMapper = JsonMapper.builder().build();
    private final PromptLoader promptLoader = new PromptLoader(objectMapper);
    private final AtomicReference<LlmRequest> captured = new AtomicReference<>();
    private final LlmClient capturingMock = new LlmClient() {
        @Override
        public <T> LlmResult<T> generate(LlmRequest request, Class<T> responseType) {
            captured.set(request);
            return new MockLlmClient(objectMapper).generate(request, responseType);
        }
    };

    @Test
    void 텍스트가_있는_PDF는_PDF_원본과_페이지_이미지를_함께_보낸다() {
        LlmResult<ExtractedExam> result = new QuestionExtractor(capturingMock, promptLoader)
                .extract("2026-mid.pdf", TestPdfs.textPdf(2));

        LlmRequest request = captured.get();
        assertThat(request.task()).isEqualTo("extract-questions");
        assertThat(request.systemPrompt()).contains("대괄호 표기 규칙", "손글씨는 모두 무시");
        assertThat(request.userPrompt()).contains("2026-mid.pdf", "2페이지", "PDF 원본(텍스트 포함) 1개 + 페이지 이미지 2장");
        assertThat(request.files()).extracting(LlmFile::mimeType)
                .containsExactly("application/pdf", "image/jpeg", "image/jpeg");

        // Mock 응답(resources/mock-llm/extract-questions.json)도 구조 점검을 통과해야 한다
        assertThat(result.value().questions()).isNotEmpty();
        assertThat(ExtractionChecker.check(result.value())).isEmpty();
    }

    @Test
    void 스캔본은_페이지_이미지만_보낸다() {
        new QuestionExtractor(capturingMock, promptLoader).extract("scan.pdf", TestPdfs.blankPdf(3));

        assertThat(captured.get().files()).extracting(LlmFile::mimeType)
                .containsExactly("image/jpeg", "image/jpeg", "image/jpeg");
        assertThat(captured.get().userPrompt()).contains("스캔본이라 텍스트 없음, 페이지 이미지 3장");
    }

    @Test
    void 어구_배열의_보기가_한_덩어리면_슬래시로_나눈다() {
        ExtractedExam exam = new ExtractedExam(List.of(), List.of(
                new Question(1, SUBJECTIVE, QuestionType.SENTENCE_ORDER, List.of(), "배열하시오", null, List.of(),
                        List.of("to employ / dangerous / how /  factories"), null, null),
                new Question(2, OBJECTIVE, QuestionType.OBJ_SUMMARY, List.of(), "고르시오", null, List.of(),
                        List.of("rise / fall", "a", "b", "c", "d"), null, null)),
                List.of());

        List<Question> questions = QuestionExtractor.normalize(exam).questions();

        assertThat(questions.get(0).choices()).containsExactly("to employ", "dangerous", "how", "factories");
        assertThat(questions.get(1).choices()).first().isEqualTo("rise / fall");
    }
}
