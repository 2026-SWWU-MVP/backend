package com.smwu.backend.pastexam.dto;

import com.smwu.backend.pastexam.domain.ExamType;
import com.smwu.backend.pastexam.domain.PastExam;
import com.smwu.backend.pastexam.domain.PastExamStatus;

import java.time.LocalDateTime;

/**
 * 기출 시험지 정보와 추출 상태. 추출 중에는 프론트가 GET /api/past-exams/{id}를 폴링해서 status를 확인한다.
 *
 * @param scanned          스캔본(사진 → PDF)이면 true
 * @param objectiveCount   추출된 객관식 문항 수 (추출 전에는 0)
 * @param subjectiveCount  추출된 서답형 문항 수 (추출 전에는 0)
 * @param failureReason    status=FAILED일 때 사유
 */
public record PastExamResponse(
        Long id,
        Long workspaceId,
        Integer examYear,
        Integer semester,
        ExamType examType,
        String originalFilename,
        int pageCount,
        boolean scanned,
        PastExamStatus status,
        String failureReason,
        long objectiveCount,
        long subjectiveCount,
        LocalDateTime createdAt,
        LocalDateTime extractedAt
) {

    public static PastExamResponse of(PastExam exam, long objectiveCount, long subjectiveCount) {
        return new PastExamResponse(exam.getId(), exam.getWorkspaceId(), exam.getExamYear(), exam.getSemester(),
                exam.getExamType(), exam.getOriginalFilename(), exam.getPageCount(), !exam.isTextLayer(),
                exam.getStatus(), exam.getFailureReason(), objectiveCount, subjectiveCount,
                exam.getCreatedAt(), exam.getExtractedAt());
    }
}
