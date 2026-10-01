package com.smwu.backend.pastexam.domain;

import com.smwu.backend.common.exception.BusinessException;
import com.smwu.backend.common.exception.ErrorCode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PastExamTest {

    @Test
    void 추출_중에는_다시_추출하거나_수정할_수_없다() {
        PastExam exam = newExam();
        exam.startExtraction();

        assertThat(exam.getStatus()).isEqualTo(PastExamStatus.EXTRACTING);
        assertThatThrownBy(exam::startExtraction)
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.EXTRACTION_IN_PROGRESS);
        assertThatThrownBy(exam::requireExtracted)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.EXTRACTION_IN_PROGRESS);
    }

    @Test
    void 실패하면_사유를_남기고_다시_추출할_수_있다() {
        PastExam exam = newExam();
        exam.startExtraction();
        exam.failExtraction("x".repeat(1500));

        assertThat(exam.getStatus()).isEqualTo(PastExamStatus.FAILED);
        assertThat(exam.getFailureReason()).hasSize(1000);
        assertThatThrownBy(exam::requireExtracted)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.EXTRACTION_NOT_COMPLETED);

        exam.startExtraction();
        assertThat(exam.getFailureReason()).isNull();
        exam.completeExtraction(List.of("경고"), "gpt-test", 10, 20, 1234, "{}");
        assertThat(exam.getStatus()).isEqualTo(PastExamStatus.EXTRACTED);
        assertThat(exam.getWarnings()).containsExactly("경고");
        exam.requireExtracted();
    }

    private static PastExam newExam() {
        return PastExam.builder()
                .workspaceId(1L).examYear(2025).semester(1).examType(ExamType.MIDTERM)
                .originalFilename("a.pdf").filePath("past-exams/a.pdf").fileSize(10).pageCount(1).textLayer(true)
                .build();
    }
}
