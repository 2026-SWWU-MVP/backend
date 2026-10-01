package com.smwu.backend.pastexam.extraction;

import com.smwu.backend.ai.LlmClient;
import com.smwu.backend.ai.LlmFile;
import com.smwu.backend.ai.LlmRequest;
import com.smwu.backend.ai.LlmResult;
import com.smwu.backend.ai.prompt.PromptLoader;
import com.smwu.backend.document.PdfPageImages;
import com.smwu.backend.pastexam.extraction.ExtractedExam.Question;
import com.smwu.backend.pastexam.extraction.ExtractedExam.QuestionSection;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 기출 PDF를 멀티모달 LLM에 넣어 지문·문항 단위 JSON으로 추출한다.
 * <ul>
 *   <li>모든 페이지를 이미지로 렌더링해서 보낸다. 텍스트 레이어가 있는 PDF는 제공사가 텍스트만 넘겨 밑줄이 사라지기 때문</li>
 *   <li>텍스트 레이어가 있으면 철자 정확도를 위해 PDF 원본도 함께 보낸다. 스캔본은 이미지만 보낸다</li>
 * </ul>
 * 프롬프트: prompts/extract-questions-system.txt, extract-questions-user.txt / 스키마: extract-questions.schema.json
 */
@Component
@RequiredArgsConstructor
public class QuestionExtractor {

    public static final String TASK = "extract-questions";

    /** [보기]를 "a / b / c" 한 덩어리로 돌려주는 경우가 있어 코드에서 나누는 유형 */
    private static final Set<QuestionType> SPLIT_CHOICE_TYPES = Set.of(QuestionType.SENTENCE_ORDER, QuestionType.GUIDED_WRITING);

    private final LlmClient llmClient;
    private final PromptLoader promptLoader;

    public LlmResult<ExtractedExam> extract(String filename, byte[] pdf) {
        boolean hasText = PdfPageImages.hasTextLayer(pdf);
        List<byte[]> pages = PdfPageImages.renderJpeg(pdf, PdfPageImages.DEFAULT_DPI);

        List<LlmFile> files = new ArrayList<>();
        if (hasText) {
            files.add(LlmFile.pdf(filename, pdf));
        }
        for (int i = 0; i < pages.size(); i++) {
            files.add(LlmFile.jpeg("page-" + (i + 1) + ".jpg", pages.get(i)));
        }
        String attachments = (hasText ? "PDF 원본(텍스트 포함) 1개 + " : "스캔본이라 텍스트 없음, ")
                + "페이지 이미지 " + pages.size() + "장 (1페이지부터 순서대로)";

        LlmRequest request = LlmRequest.of(
                        TASK,
                        promptLoader.text(TASK + "-system"),
                        promptLoader.render(TASK + "-user", Map.of(
                                "filename", filename,
                                "pageCount", pages.size(),
                                "attachments", attachments)),
                        promptLoader.schema(TASK))
                .withFiles(files);

        LlmResult<ExtractedExam> result = llmClient.generate(request, ExtractedExam.class);
        return new LlmResult<>(normalize(result.value()), result.rawText(), result.model(),
                result.inputTokens(), result.outputTokens());
    }

    /** 모델 출력의 형식 차이를 코드로 정리한다 */
    static ExtractedExam normalize(ExtractedExam exam) {
        List<Question> questions = exam.questions().stream()
                .map(QuestionExtractor::splitSlashChoices)
                .toList();
        return new ExtractedExam(exam.passages(), questions, exam.warnings());
    }

    private static Question splitSlashChoices(Question q) {
        if (q.section() != QuestionSection.SUBJECTIVE || !SPLIT_CHOICE_TYPES.contains(q.type())
                || q.choices().stream().noneMatch(c -> c.contains("/"))) {
            return q;
        }
        List<String> split = q.choices().stream()
                .flatMap(c -> Arrays.stream(c.split("/")))
                .map(String::strip)
                .filter(c -> !c.isEmpty())
                .toList();
        return new Question(q.no(), q.section(), q.type(), q.passageIds(), q.stem(), q.body(),
                q.conditions(), split, q.answer(), q.points());
    }
}
