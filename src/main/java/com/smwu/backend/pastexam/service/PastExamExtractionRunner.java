package com.smwu.backend.pastexam.service;

import com.smwu.backend.ai.LlmException;
import com.smwu.backend.ai.LlmResult;
import com.smwu.backend.common.config.AsyncConfig;
import com.smwu.backend.common.storage.FileStorage;
import com.smwu.backend.pastexam.domain.PastExam;
import com.smwu.backend.pastexam.extraction.ExtractedExam;
import com.smwu.backend.pastexam.extraction.QuestionExtractor;
import com.smwu.backend.pastexam.repository.PastExamRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * 백그라운드 추출 작업. LLM 호출(1~2분) 동안 DB 트랜잭션을 열어두지 않고,
 * 결과 저장만 {@link PastExamResultWriter}의 짧은 트랜잭션으로 처리한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PastExamExtractionRunner {

    private final PastExamRepository pastExamRepository;
    private final FileStorage fileStorage;
    private final QuestionExtractor questionExtractor;
    private final PastExamResultWriter resultWriter;

    @Async(AsyncConfig.EXTRACTION_EXECUTOR)
    public void run(Long examId) {
        PastExam exam = pastExamRepository.findById(examId).orElse(null);
        if (exam == null) {
            return;
        }
        long startedAt = System.currentTimeMillis();
        try {
            byte[] pdf = fileStorage.read(exam.getFilePath());
            LlmResult<ExtractedExam> result = questionExtractor.extract(exam.getOriginalFilename(), pdf);
            resultWriter.saveResult(examId, result, System.currentTimeMillis() - startedAt);
            log.info("기출 추출 완료 examId={} 문항 {}개 {}ms", examId, result.value().questions().size(),
                    System.currentTimeMillis() - startedAt);
        } catch (LlmException e) {
            log.warn("기출 추출 실패 examId={}: {}", examId, e.getDetail());
            resultWriter.markFailed(examId, "AI가 시험지를 읽지 못했습니다. 잠시 후 다시 추출해 주세요.");
        } catch (RuntimeException e) {
            log.error("기출 추출 중 오류 examId={}", examId, e);
            resultWriter.markFailed(examId, "추출 중 오류가 발생했습니다. 다시 추출해 주세요.");
        }
    }
}
