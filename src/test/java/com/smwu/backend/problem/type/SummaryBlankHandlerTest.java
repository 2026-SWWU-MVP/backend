package com.smwu.backend.problem.type;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SummaryBlankHandlerTest {

    static final PassageSource PASSAGE = new PassageSource(1L, "Bringing New Life to Old Cities",
            "As cities age, neighborhoods can become old and lifeless, which may cause citizens to move away. "
                    + "When this happens, a collaboration between the local government and citizens is an effective way "
                    + "to revitalize the area. Let's take a look at two projects in the Netherlands.");

    private final SummaryBlankHandler handler = new SummaryBlankHandler();

    @Test
    void 형태_변경_없음_조건과_빈칸_표기를_조립한다() {
        ProblemOptions options = new ProblemOptions(2, false, null).withDefaults();
        AssembledProblem p = handler.assemble(new SummaryBlankHandler.Draft(
                "As a neighborhood ages, it may become [[lifeless]] and lose residents. Cooperation between the local "
                        + "government and citizens can help [[revitalize]] the area.",
                "When this happens, a collaboration between the local government and citizens is an effective way to revitalize the area.",
                "도시가 낡으면 활기를 잃고, 협력이 지역을 되살린다는 요지이다."), PASSAGE, options, 1L);

        assertThat(p.stem()).isEqualTo("윗글의 내용을 요약할 때, 빈칸 (1), (2)에 들어갈 말을 쓰시오.");
        assertThat(p.conditions()).containsExactly("각 빈칸에 한 단어씩 쓸 것.", "윗글에 나온 단어를 형태 변경 없이 쓸 것.");
        assertThat(p.body()).isEqualTo("As a neighborhood ages, it may become (1) __________ and lose residents. "
                + "Cooperation between the local government and citizens can help (2) __________ the area.");
        assertThat(p.answer().blanks()).containsExactly("lifeless", "revitalize");
        assertThat(p.answerText()).isEqualTo("(1) lifeless   (2) revitalize");
        assertThat(handler.validate(p, PASSAGE, options)).allMatch(ValidationCheck::passed);
    }

    @Test
    void 첫_철자_제시형은_첫_글자를_보여주고_윗글에_없는_단어도_허용한다() {
        ProblemOptions options = new ProblemOptions(3, true, null).withDefaults();
        AssembledProblem p = handler.assemble(new SummaryBlankHandler.Draft(
                "Aging neighborhoods may grow [[lifeless]] and [[lose]] residents, but cooperation between the government "
                        + "and citizens can [[restore]] them.", "As cities age, neighborhoods can become old and lifeless.", "해설"),
                PASSAGE, options, 1L);

        assertThat(p.stem()).isEqualTo("윗글의 내용을 요약할 때, 빈칸 (1)~(3)에 들어갈 말을 쓰시오.");
        assertThat(p.conditions()).contains("제시된 첫 철자로 시작할 것.", "문맥에 맞는 형태로 쓸 것.");
        assertThat(p.body()).contains("(1) l________", "(2) l________", "(3) r________");
        assertThat(handler.validate(p, PASSAGE, options)).allMatch(ValidationCheck::passed);
    }

    @Test
    void 빈칸_수가_다르면_조립할_수_없다() {
        ProblemOptions options = new ProblemOptions(2, false, null).withDefaults();

        assertThatThrownBy(() -> handler.assemble(new SummaryBlankHandler.Draft("Only [[one]] blank here in this summary.", "x", "y"),
                PASSAGE, options, 1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("요약문의 [[단어]] 표시가 2개여야 하는데 1개다.");
    }

    @Test
    void 규칙_위반을_찾는다() {
        ProblemOptions options = new ProblemOptions(3, false, null).withDefaults();
        AssembledProblem p = handler.assemble(new SummaryBlankHandler.Draft(
                "As cities age, neighborhoods can become old and [[lifeless]], which may cause [[citizens]] to [[move away]].",
                "x", "y"), PASSAGE, options, 1L);

        List<ValidationCheck> failed = handler.validate(p, PASSAGE, options).stream().filter(c -> !c.passed()).toList();

        assertThat(failed).extracting(ValidationCheck::name).containsExactlyInAnyOrder("SINGLE_WORD", "WORD_IN_PASSAGE", "NOT_COPIED");
    }

    @Test
    void 기능어나_너무_짧은_정답은_실패하고_해설의_표시는_지운다() {
        ProblemOptions options = new ProblemOptions(2, false, null).withDefaults();
        AssembledProblem p = handler.assemble(new SummaryBlankHandler.Draft(
                "Aging towns lose people, and [[which]] is why leaders and [[citizens]] must cooperate to save them.", "x",
                "정답은 [[citizens]]이다."), PASSAGE, options, 1L);

        assertThat(handler.validate(p, PASSAGE, options).stream().filter(c -> !c.passed()))
                .extracting(ValidationCheck::name).contains("CONTENT_WORD");
        assertThat(p.explanation()).isEqualTo("정답은 citizens이다.");
    }

    @Test
    void 정답이_겹치거나_윗글에_없는_형태면_실패() {
        ProblemOptions options = new ProblemOptions(2, false, null).withDefaults();
        AssembledProblem p = handler.assemble(new SummaryBlankHandler.Draft(
                "Old towns become [[revitalized]] when people and leaders work together to [[Revitalized]] them again.", "x", "y"),
                PASSAGE, options, 1L);

        assertThat(handler.validate(p, PASSAGE, options).stream().filter(c -> !c.passed()))
                .extracting(ValidationCheck::name)
                .containsExactlyInAnyOrder("DISTINCT_ANSWERS", "WORD_IN_PASSAGE");
    }
}
