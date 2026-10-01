package com.smwu.backend.pastexam.dto;

import com.smwu.backend.pastexam.domain.ExamType;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/** 기출 업로드 시 함께 보내는 시험 정보 (multipart 폼 필드) */
public record UploadPastExamRequest(
        @NotNull(message = "시험 연도는 필수입니다.")
        @Min(value = 2000, message = "시험 연도가 올바르지 않습니다.")
        @Max(value = 2100, message = "시험 연도가 올바르지 않습니다.")
        Integer examYear,

        @NotNull(message = "학기는 필수입니다.")
        @Min(value = 1, message = "학기는 1 또는 2입니다.")
        @Max(value = 2, message = "학기는 1 또는 2입니다.")
        Integer semester,

        @NotNull(message = "시험 종류(MIDTERM/FINAL)는 필수입니다.")
        ExamType examType
) {
}
