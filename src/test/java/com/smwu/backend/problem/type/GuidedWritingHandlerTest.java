package com.smwu.backend.problem.type;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GuidedWritingHandlerTest {

    private static final String SENTENCE = "When this happens, a collaboration between the local government and citizens is an "
            + "effective way to revitalize the area.";

    private final GuidedWritingHandler handler = new GuidedWritingHandler();
    private final ProblemOptions options = ProblemOptions.defaults();

    @Test
    void 우리말_해석과_제시어를_본문으로_단어_수를_조건으로_조립한다() {
        AssembledProblem p = handler.assemble(new GuidedWritingHandler.Draft(SENTENCE,
                "이런 일이 일어날 때, 지방 정부와 시민 간의 협력은 그 지역을 되살리는 효과적인 방법이다.",
                List.of("collaboration", "government", "effective", "revitalize"), "해설"), GrammarFixHandlerTest.PASSAGE, options, 1L);

        assertThat(p.conditions()).containsExactly("주어진 단어를 모두 사용할 것.", "필요한 경우 어형을 바꿀 것.", "19단어로 쓸 것.");
        assertThat(p.body()).isEqualTo("이런 일이 일어날 때, 지방 정부와 시민 간의 협력은 그 지역을 되살리는 효과적인 방법이다.\n"
                + "(collaboration, government, effective, revitalize)");
        assertThat(p.answerText()).isEqualTo(SENTENCE);
        assertThat(handler.validate(p, GrammarFixHandlerTest.PASSAGE, options)).allMatch(ValidationCheck::passed);
    }

    @Test
    void 제시어가_문장에_없거나_너무_많거나_해석이_한국어가_아니면_실패() {
        AssembledProblem p = handler.assemble(new GuidedWritingHandler.Draft(SENTENCE, "When this happens...",
                List.of("collaboration", "government", "effective", "revitalize", "local", "citizen", "bring", "this", "area", "way"),
                "해설"), GrammarFixHandlerTest.PASSAGE, options, 1L);

        assertThat(handler.validate(p, GrammarFixHandlerTest.PASSAGE, options).stream().filter(c -> !c.passed()))
                .extracting(ValidationCheck::name)
                .containsExactlyInAnyOrder("KOREAN", "GIVEN_COUNT", "GIVEN_IN_SENTENCE", "NOT_TOO_EASY");
    }

    @Test
    void 원형과_규칙_변화형을_같은_단어로_본다() {
        assertThat(GuidedWritingHandler.sameLemma("seem", "seemed")).isTrue();
        assertThat(GuidedWritingHandler.sameLemma("revitalize", "revitalizing")).isTrue();
        assertThat(GuidedWritingHandler.sameLemma("study", "studies")).isTrue();
        assertThat(GuidedWritingHandler.sameLemma("stop", "stopped")).isTrue();
        assertThat(GuidedWritingHandler.sameLemma("building", "buildings")).isTrue();
        assertThat(GuidedWritingHandler.sameLemma("bring", "brought")).isFalse();
        assertThat(GuidedWritingHandler.sameLemma("care", "careful")).isFalse();
    }
}
