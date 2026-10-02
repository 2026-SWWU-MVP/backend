package com.smwu.backend.problem.type;

import com.smwu.backend.problem.type.GrammarFixHandler.Target;
import com.smwu.backend.problem.type.ProblemAnswer.Correction;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GrammarFixHandlerTest {

    static final PassageSource PASSAGE = new PassageSource(1L, null,
            "As cities age, neighborhoods can become old and lifeless, which may cause citizens to move away. "
                    + "When this happens, a collaboration between the local government and citizens is an effective way "
                    + "to revitalize the area. Let's take a look at two projects in the Netherlands to find out what "
                    + "successful urban revitalization looks like.");
    static final List<Target> TARGETS = List.of(
            new Target("neighborhoods can", "become", "become"),
            new Target("may cause citizens", "to move", "moving"),
            new Target("government and citizens", "is", "is"),
            new Target("an effective way to", "revitalize", "revitalize"),
            new Target("Netherlands to find out", "what", "what"));

    private final GrammarFixHandler handler = new GrammarFixHandler();
    private final ProblemOptions options = ProblemOptions.defaults();

    @Test
    void 원문에서_밑줄_위치를_찾아_번호와_대괄호를_넣고_나머지는_그대로_둔다() {
        AssembledProblem p = handler.assemble(new GrammarFixHandler.Draft(TARGETS, "cause는 to부정사를 목적격 보어로 쓴다."),
                PASSAGE, options, 1L);

        assertThat(p.stem()).isEqualTo("윗글의 밑줄 친 ①~⑤ 중 어법상 틀린 것을 1개 찾아 바르게 고쳐 쓰시오.");
        assertThat(p.body()).isEqualTo("As cities age, neighborhoods can ①[become] old and lifeless, which may cause citizens ②[moving] away. "
                + "When this happens, a collaboration between the local government and citizens ③[is] an effective way "
                + "to ④[revitalize] the area. Let's take a look at two projects in the Netherlands to find out ⑤[what] "
                + "successful urban revitalization looks like.");
        assertThat(p.answer().corrections()).containsExactly(new Correction(2, "moving", "to move"));
        assertThat(p.answerText()).isEqualTo("② moving → to move");
        assertThat(p.evidence()).isEqualTo("may cause citizens to move");
        assertThat(handler.validate(p, PASSAGE, options)).allMatch(ValidationCheck::passed);
    }

    @Test
    void 틀린_곳_2개_옵션과_개수_검증() {
        List<Target> twoErrors = new ArrayList<>(TARGETS);
        twoErrors.set(2, new Target("government and citizens", "is", "are"));
        ProblemOptions two = new ProblemOptions(null, null, null, 2).withDefaults();

        AssembledProblem p = handler.assemble(new GrammarFixHandler.Draft(twoErrors, "x"), PASSAGE, two, 1L);

        assertThat(p.stem()).contains("2개 찾아");
        assertThat(p.answerText()).isEqualTo("② moving → to move   ③ are → is");
        assertThat(handler.validate(p, PASSAGE, two)).allMatch(ValidationCheck::passed);
        assertThat(handler.validate(p, PASSAGE, options)).filteredOn(c -> !c.passed())
                .extracting(ValidationCheck::name).containsExactly("ERROR_COUNT");
    }

    @Test
    void 원문에_없거나_순서가_틀린_밑줄은_조립할_수_없다() {
        List<Target> missing = new ArrayList<>(TARGETS);
        missing.set(1, new Target("may force people", "to leave", "leaving"));
        List<Target> reversed = new ArrayList<>(TARGETS);
        reversed.set(0, TARGETS.get(4));
        reversed.set(4, TARGETS.get(0));

        assertThatThrownBy(() -> handler.assemble(new GrammarFixHandler.Draft(missing, "x"), PASSAGE, options, 1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("2번 밑줄 \"may force people to leave\"을 윗글에서");
        assertThatThrownBy(() -> handler.assemble(new GrammarFixHandler.Draft(reversed, "x"), PASSAGE, options, 1L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> handler.assemble(new GrammarFixHandler.Draft(TARGETS.subList(0, 3), "x"), PASSAGE, options, 1L))
                .hasMessage("밑줄(targets)이 5개여야 하는데 3개다.");
    }

    @Test
    void 앞_단어가_달라도_원래_표현이_한_번만_나오면_그_위치를_쓰고_여러_번이면_실패() {
        List<Target> looseBefore = new ArrayList<>(TARGETS);
        looseBefore.set(1, new Target("which might force citizens", "to move", "moving"));
        AssembledProblem p = handler.assemble(new GrammarFixHandler.Draft(looseBefore, "x"), PASSAGE, options, 1L);
        assertThat(p.body()).contains("citizens ②[moving] away");

        List<Target> ambiguous = new ArrayList<>(TARGETS);
        ambiguous.set(1, new Target("wrong words here", "the", "a"));
        assertThatThrownBy(() -> handler.assemble(new GrammarFixHandler.Draft(ambiguous, "x"), PASSAGE, options, 1L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 아포스트로피와_문장부호가_달라도_위치를_찾는다() {
        PassageSource passage = new PassageSource(null, null, "Let’s take a look, then, at two projects that were finished.");
        assertThat(GrammarFixHandler.locate(new Target("Let's take a look then at", "two", "two")).matcher(passage.text()).find()).isTrue();
    }
}
