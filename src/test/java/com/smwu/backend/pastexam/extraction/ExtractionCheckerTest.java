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
                        question(1, OBJECTIVE, QuestionType.OBJ_GRAMMAR, List.of("P1"), List.of()),
                        question(1, SUBJECTIVE, QuestionType.SENTENCE_ORDER, List.of("P1"), List.of("it could", "become", "a place"))),
                List.of());

        assertThat(ExtractionChecker.check(exam)).isEmpty();
    }

    @Test
    void 선택지_번호가_지문_안에_있으면_choices가_비어도_된다() {
        ExtractedExam exam = new ExtractedExam(
                List.of(new Passage("P1", null, "Intro. ①One. ②Two. ③Three. ④Four. ⑤Five.")),
                List.of(question(1, OBJECTIVE, QuestionType.OBJ_IRRELEVANT, List.of("P1"), List.of())),
                List.of());

        assertThat(ExtractionChecker.check(exam)).isEmpty();
    }

    @Test
    void 어법_문장_고르기형은_선택지_5개면_통과하고_듣기는_지문이_없어도_된다() {
        ExtractedExam exam = new ExtractedExam(
                List.of(),
                List.of(
                        question(10, OBJECTIVE, QuestionType.OBJ_GRAMMAR, List.of(), FIVE),
                        question(1, OBJECTIVE, QuestionType.OBJ_LISTENING, List.of(), FIVE)),
                List.of());

        assertThat(ExtractionChecker.check(exam)).isEmpty();
    }

    @Test
    void 선택지가_본문에_있거나_배열_어구가_본문_조건에_있으면_통과한다() {
        ExtractedExam exam = new ExtractedExam(
                List.of(new Passage("P1", null, "(A)[accumulating] (B)[Knowing] (C)[Reading] (D)[Getting] (E)[taking]")),
                List.of(
                        // 문장 고르기형 어법: ①~⑤ 문장이 body에 있음
                        new Question(10, OBJECTIVE, QuestionType.OBJ_GRAMMAR, List.of(), "모두 고르시오", "① A. ② B. ③ C. ④ D. ⑤ E.",
                                List.of(), List.of(), "①②⑤", 2.2),
                        // (A)~(E) 밑줄형
                        question(18, OBJECTIVE, QuestionType.OBJ_GRAMMAR, List.of("P1"), List.of()),
                        // 어구가 본문 괄호 안에 있음
                        new Question(2, SUBJECTIVE, QuestionType.SENTENCE_ORDER, List.of("P1"), "쓰시오", "ⓐ : ______ ( men / seem )",
                                List.of(), List.of(), null, 17.0),
                        // 어구가 [조건]에 쉼표로 나열됨
                        new Question(7, SUBJECTIVE, QuestionType.SENTENCE_ORDER, List.of("P1"), "완성하시오", "______ ______",
                                List.of("lack, evolve, describe, correctly, the, to을 모두 한 번씩 사용할 것"), List.of(), null, null)),
                List.of());

        assertThat(ExtractionChecker.check(exam)).isEmpty();
    }

    @Test
    void 어법_밑줄_대괄호가_빠지면_알려준다() {
        ExtractedExam exam = new ExtractedExam(
                List.of(new Passage("P1", null, "It was abandoned and ②[taking] over."),
                        new Passage("P2", null, "No underline at all.")),
                List.of(
                        question(3, OBJECTIVE, QuestionType.OBJ_GRAMMAR, List.of("P1"), List.of()),
                        question(4, OBJECTIVE, QuestionType.OBJ_GRAMMAR, List.of("P2"), List.of())),
                List.of());

        assertThat(ExtractionChecker.check(exam)).contains(
                "3번: 어법 객관식 밑줄 대괄호가 1개 (5개 예상, 원본 확인 필요)",
                "4번: 어법 객관식인데 밑줄 대괄호도, 선택지 5개도 없음 (밑줄 → 대괄호 변환 확인 필요)");
    }

    @Test
    void 기호_밑줄과_여러_지문_참조를_인식한다() {
        ExtractedExam exam = new ExtractedExam(
                List.of(new Passage("P1", null, "(A) ⓐ[After spending years] ⓑ[she lifted it] ⓒ[good news]"),
                        new Passage("P2", null, "(B) ⓓ[Some birds] ⓔ[It was shocked]")),
                List.of(question(6, OBJECTIVE, QuestionType.OBJ_GRAMMAR, List.of("P1", "P2"), FIVE)),
                List.of());

        assertThat(ExtractionChecker.check(exam)).isEmpty();
        assertThat(ExtractionChecker.countMarkedBrackets("ⓐ[a] ①[b] (A)[c / d] [plain]")).isEqualTo(3);
    }

    @Test
    void 번호_중복_없는_지문_참조_쓰이지_않는_지문을_찾는다() {
        ExtractedExam exam = new ExtractedExam(
                List.of(new Passage("P1", null, "text"), new Passage("P2", null, "unused")),
                List.of(
                        question(1, OBJECTIVE, QuestionType.OBJ_MAIN_IDEA, List.of("P1"), FIVE),
                        question(1, OBJECTIVE, QuestionType.OBJ_DETAIL, List.of("P9"), FIVE)),
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
                List.of(question(2, SUBJECTIVE, QuestionType.OBJ_BLANK, List.of("P1"), List.of())),
                List.of());

        assertThat(ExtractionChecker.check(exam)).contains(
                "서술형 2번: 판독불가 부분 있음",
                "서술형 2번: 서술형인데 객관식 유형 OBJ_BLANK",
                "지문 P1: 대괄호 짝이 맞지 않음");
    }

    private static Question question(int no, ExtractedExam.QuestionSection section, QuestionType type,
                                     List<String> passageIds, List<String> choices) {
        return new Question(no, section, type, passageIds, "발문", null, List.of(), choices, null, null);
    }
}
