package com.smwu.backend.pastexam.dto;

import com.smwu.backend.pastexam.extraction.ExtractedExam.QuestionSection;
import com.smwu.backend.pastexam.extraction.QuestionType;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 추출된 문항 수정. 보낸 필드만 바뀐다 (null은 그대로).
 * body·answer는 빈 문자열을 보내면 지워지고, 배점을 지우려면 clearPoints=true.
 */
public record UpdatePastQuestionRequest(
        QuestionSection section,
        @Min(value = 1, message = "문항 번호는 1 이상입니다.") Integer no,
        QuestionType type,
        List<String> passageCodes,
        @Size(min = 1, max = 2000, message = "발문은 1~2000자입니다.") String stem,
        String body,
        List<String> conditions,
        List<String> choices,
        @Size(max = 4000, message = "정답은 4000자 이하입니다.") String answer,
        @Positive(message = "배점은 0보다 커야 합니다.") Double points,
        Boolean clearPoints
) {
}
