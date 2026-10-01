package com.smwu.backend.pastexam.dto;

import com.smwu.backend.pastexam.domain.PastExamStatus;
import com.smwu.backend.pastexam.domain.PastPassage;
import com.smwu.backend.pastexam.domain.PastQuestion;
import com.smwu.backend.pastexam.extraction.ExtractedExam.QuestionSection;
import com.smwu.backend.pastexam.extraction.QuestionType;

import java.util.List;

/**
 * 기출 추출 결과 검수 화면용 응답.
 * issues는 코드 점검(ExtractionChecker) 결과로, 조회할 때마다 현재 내용으로 다시 계산한다.
 * 이슈가 있는 문항·지문을 강조해서 강사가 원본과 비교하도록 한다.
 *
 * @param examIssues 특정 문항·지문에 속하지 않는 이슈 (문항 없음 등)
 * @param warnings   모델이 추출하면서 남긴 메모 (판독 불가, 애매한 부분)
 * @param issueCount 전체 이슈 수 (문항 + 지문 + 시험지)
 */
public record ExtractionResultResponse(
        Long examId,
        PastExamStatus status,
        List<PassageView> passages,
        List<QuestionView> questions,
        List<String> examIssues,
        List<String> warnings,
        int issueCount
) {

    public record PassageView(Long id, String code, String title, String text, boolean edited, List<String> issues) {

        public static PassageView of(PastPassage passage, List<String> issues) {
            return new PassageView(passage.getId(), passage.getCode(), passage.getTitle(), passage.getText(),
                    passage.isEdited(), issues);
        }
    }

    public record QuestionView(
            Long id,
            int orderNo,
            QuestionSection section,
            int no,
            QuestionType type,
            List<String> passageCodes,
            String stem,
            String body,
            List<String> conditions,
            List<String> choices,
            String answer,
            Double points,
            boolean edited,
            List<String> issues
    ) {

        public static QuestionView of(PastQuestion q, List<String> issues) {
            return new QuestionView(q.getId(), q.getOrderNo(), q.getSection(), q.getNo(), q.getType(),
                    List.copyOf(q.getPassageCodes()), q.getStem(), q.getBody(), List.copyOf(q.getConditions()),
                    List.copyOf(q.getChoices()), q.getAnswer(), q.getPoints(), q.isEdited(), issues);
        }
    }
}
