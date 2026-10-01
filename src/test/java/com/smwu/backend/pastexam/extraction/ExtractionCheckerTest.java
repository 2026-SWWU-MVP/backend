package com.smwu.backend.pastexam.extraction;

import com.smwu.backend.pastexam.extraction.ExtractedExam.Passage;
import com.smwu.backend.pastexam.extraction.ExtractedExam.Question;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.smwu.backend.pastexam.extraction.ExtractedExam.QuestionSection.OBJECTIVE;
import static com.smwu.backend.pastexam.extraction.ExtractedExam.QuestionSection.SUBJECTIVE;
import static org.assertj.core.api.Assertions.assertThat;

class ExtractionCheckerTest {

    private static final List<String> FIVE = List.of("a", "b", "c", "d", "e");

    @Test
    void 구조가_올바르면_문제가_없다() {
        ExtractedExam exam = new ExtractedExam(
                List.of(new Passage("P1", "Old Cities", "It ①[was] abandoned and ②[taking] over, ③[which] ④[is] ⑤[done].")),
                List.of(
                        objective(1, QuestionType.OBJ_GRAMMAR, "P1"),
                        subjective(1, QuestionType.SENTENCE_ORDER, "P1", List.of("it could", "become", "a place"))),
                List.of());

        assertThat(ExtractionChecker.check(exam)).isEmpty();
    }

    @Test
    void 어법_객관식인데_대괄호가_5개가_아니면_알려준다() {
        ExtractedExam exam = new ExtractedExam(
                List.of(new Passage("P1", null, "It was abandoned and ②[taking] over.")),
                List.of(new Question(3, OBJECTIVE, QuestionType.OBJ_GRAMMAR, "P1", "어법상 틀린 것은?", null,
                        List.of(), List.of(), null, 3.0)),
                List.of());

        assertThat(ExtractionChecker.check(exam))
                .contains("3번: 어법 객관식인데 ①[ ]~⑤[ ] 표기가 1개 (밑줄 → 대괄호 변환 확인 필요)",
                        "3번: 객관식 선택지가 0개");
    }

    @Test
    void 번호_중복_없는_지문_참조_쓰이지_않는_지문을_찾는다() {
        ExtractedExam exam = new ExtractedExam(
                List.of(new Passage("P1", null, "text"), new Passage("P2", null, "unused")),
                List.of(
                        objective(1, QuestionType.OBJ_MAIN_IDEA, "P1"),
                        objective(1, QuestionType.OBJ_DETAIL, "P9")),
                List.of());

        assertThat(ExtractionChecker.check(exam)).containsExactlyInAnyOrder(
                "1번: 문항 번호 중복",
                "1번: 없는 지문 P9 참조",
                "지문 P2: 참조하는 문항이 없음");
    }

    @Test
    void 대괄호_짝과_판독불가_섹션과_유형_불일치를_찾는다() {
        ExtractedExam exam = new ExtractedExam(
                List.of(new Passage("P1", null, "broken [bracket and [판독불가]")),
                List.of(subjective(2, QuestionType.OBJ_BLANK, "P1", List.of())),
                List.of());

        assertThat(ExtractionChecker.check(exam)).contains(
                "서술형 2번: 대괄호 짝이 맞지 않음",
                "서술형 2번: 판독불가 부분 있음",
                "서술형 2번: 서술형인데 객관식 유형 OBJ_BLANK",
                "지문 P1: 대괄호 짝이 맞지 않음");
    }

    private static Question objective(int no, QuestionType type, String passageId) {
        return new Question(no, OBJECTIVE, type, passageId, "발문", null, List.of(), FIVE, null, null);
    }

    private static Question subjective(int no, QuestionType type, String passageId, List<String> choices) {
        return new Question(no, SUBJECTIVE, type, passageId, "발문", null, List.of(), choices, null, null);
    }
}
