package com.smwu.backend.pastexam.extraction;

import com.smwu.backend.ai.LlmClient;
import com.smwu.backend.ai.LlmFile;
import com.smwu.backend.ai.LlmRequest;
import com.smwu.backend.ai.LlmResult;
import com.smwu.backend.ai.prompt.PromptLoader;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 기출 PDF를 멀티모달 LLM에 직접 넣어 지문·문항 단위 JSON으로 추출한다.
 * 프롬프트: prompts/extract-questions-system.txt, extract-questions-user.txt / 스키마: extract-questions.schema.json
 */
@Component
@RequiredArgsConstructor
public class QuestionExtractor {

    public static final String TASK = "extract-questions";

    private final LlmClient llmClient;
    private final PromptLoader promptLoader;

    public LlmResult<ExtractedExam> extract(String filename, byte[] pdf) {
        LlmRequest request = LlmRequest.of(
                        TASK,
                        promptLoader.text(TASK + "-system"),
                        promptLoader.render(TASK + "-user", Map.of("filename", filename)),
                        promptLoader.schema(TASK))
                .withFiles(List.of(LlmFile.pdf(filename, pdf)));
        return llmClient.generate(request, ExtractedExam.class);
    }
}
