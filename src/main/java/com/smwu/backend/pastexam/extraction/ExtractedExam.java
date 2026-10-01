package com.smwu.backend.pastexam.extraction;

import java.util.List;

/**
 * 멀티모달 LLM이 기출 PDF에서 추출한 결과. 응답 스키마는 resources/prompts/extract-questions.schema.json
 *
 * @param passages  지문 목록. 여러 문항이 한 지문을 공유할 수 있다
 * @param questions 문항 목록 (시험지 순서)
 * @param warnings  읽지 못한 부분, 판단이 애매한 부분 등 모델이 남긴 메모
 */
public record ExtractedExam(
        List<Passage> passages,
        List<Question> questions,
        List<String> warnings
) {

    /**
     * @param id    문항이 참조하는 지문 ID (P1, P2 ...)
     * @param title 지문 제목. 없으면 null
     * @param text  지문 본문. 밑줄은 대괄호 규칙으로 표기
     */
    public record Passage(String id, String title, String text) {
    }

    /**
     * @param no         시험지의 문항 번호 (서술형은 서술형 번호)
     * @param section    객관식 / 서술형
     * @param type       문항 유형
     * @param passageIds 참조 지문 ID 목록. (A)(B)로 나뉜 지문처럼 여러 개일 수 있고, 지문이 없으면 빈 목록
     * @param stem       발문 (한국어 지시문)
     * @param body       지문과 별도로 문항에만 있는 본문 (요약문, 영작할 우리말 등). 없으면 null
     * @param conditions [조건] 항목. 번호 없이 한 항목씩
     * @param choices    객관식 선택지 또는 [보기] 항목. 번호 없이 순서대로
     * @param answer     시험지에 인쇄된 정답·모범답안(정답표 포함)이 있을 때만. 학생 필기나 추측은 넣지 않음
     * @param points     배점. 표시가 없으면 null
     */
    public record Question(
            int no,
            QuestionSection section,
            QuestionType type,
            List<String> passageIds,
            String stem,
            String body,
            List<String> conditions,
            List<String> choices,
            String answer,
            Double points
    ) {
    }

    public enum QuestionSection {
        OBJECTIVE, SUBJECTIVE
    }
}
