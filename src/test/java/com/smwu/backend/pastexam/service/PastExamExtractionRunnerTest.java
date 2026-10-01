package com.smwu.backend.pastexam.service;

import com.smwu.backend.ai.LlmException;
import com.smwu.backend.common.storage.FileStorage;
import com.smwu.backend.pastexam.domain.ExamType;
import com.smwu.backend.pastexam.domain.PastExam;
import com.smwu.backend.pastexam.extraction.QuestionExtractor;
import com.smwu.backend.pastexam.repository.PastExamRepository;
import org.junit.jupiter.api.Test;

import java.io.UncheckedIOException;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PastExamExtractionRunnerTest {

    private final PastExamRepository repository = mock(PastExamRepository.class);
    private final FileStorage fileStorage = mock(FileStorage.class);
    private final QuestionExtractor extractor = mock(QuestionExtractor.class);
    private final PastExamResultWriter writer = mock(PastExamResultWriter.class);
    private final PastExamExtractionRunner runner = new PastExamExtractionRunner(repository, fileStorage, extractor, writer);

    @Test
    void AI_호출이_실패하면_사용자용_사유로_실패_처리한다() {
        when(repository.findById(1L)).thenReturn(Optional.of(exam()));
        when(fileStorage.read("past-exams/a.pdf")).thenReturn(new byte[]{1});
        when(extractor.extract(any(), any())).thenThrow(new LlmException("HTTP 429 rate limit"));

        runner.run(1L);

        verify(writer).markFailed(1L, "AI가 시험지를 읽지 못했습니다. 잠시 후 다시 추출해 주세요.");
        verify(writer, never()).saveResult(anyLong(), any(), anyLong());
    }

    @Test
    void 파일을_읽지_못해도_실패_처리한다() {
        when(repository.findById(1L)).thenReturn(Optional.of(exam()));
        when(fileStorage.read(any())).thenThrow(new UncheckedIOException(new java.io.IOException("missing")));

        runner.run(1L);

        verify(writer).markFailed(1L, "추출 중 오류가 발생했습니다. 다시 추출해 주세요.");
    }

    private static PastExam exam() {
        return PastExam.builder()
                .workspaceId(1L).examYear(2025).semester(1).examType(ExamType.MIDTERM)
                .originalFilename("a.pdf").filePath("past-exams/a.pdf").fileSize(1).pageCount(1).textLayer(true)
                .build();
    }
}
