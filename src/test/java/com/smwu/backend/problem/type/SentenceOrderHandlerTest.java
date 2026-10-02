package com.smwu.backend.problem.type;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SentenceOrderHandlerTest {

    static final PassageSource PASSAGE = new PassageSource(2L, null,
            "For years, the neighborhood was known for its high crime rates. At first, the local government thought "
                    + "that the best solution was simply to tear the building down. However, having a limited budget, "
                    + "the government was unable to do so and had to come up with a new plan.");
    static final String SENTENCE = "However, having a limited budget, the government was unable to do so and had to come up with a new plan.";

    private final SentenceOrderHandler handler = new SentenceOrderHandler();
    private final ProblemOptions options = ProblemOptions.defaults();

    @Test
    void 어구를_섞어_보기를_만들고_정답_순서를_기록한다() {
        AssembledProblem p = handler.assemble(new SentenceOrderHandler.Draft(SENTENCE,
                List.of("However,", "having", "a limited budget,", "the government", "was unable", "to do so",
                        "and had to come up with", "a new plan."), "분사구문 having ~이 이유를 나타낸다."), PASSAGE, options, 42L);

        assertThat(p.stem()).isEqualTo("윗글에 나온 문장이 되도록 [보기]의 어구를 배열하시오.");
        assertThat(p.conditions()).containsExactly("[보기]의 모든 어구를 한 번씩 사용할 것.", "단어를 추가하거나 형태를 바꾸지 말 것.",
                "쉼표와 마침표를 알맞게 쓸 것.");
        // 문장부호는 [보기]에서 빠진다
        assertThat(p.choices()).containsExactlyInAnyOrder("However", "having", "a limited budget", "the government",
                "was unable", "to do so", "and had to come up with", "a new plan");
        assertThat(p.choices()).isNotEqualTo(List.of("However", "having", "a limited budget", "the government",
                "was unable", "to do so", "and had to come up with", "a new plan"));
        List<String> ordered = p.answer().chunkOrder().stream().map(p.choices()::get).toList();
        assertThat(ordered).containsExactly("However", "having", "a limited budget", "the government", "was unable",
                "to do so", "and had to come up with", "a new plan");
        assertThat(p.answerText()).isEqualTo(SENTENCE);
        assertThat(handler.validate(p, PASSAGE, options)).allMatch(ValidationCheck::passed);
    }

    @Test
    void 같은_시드면_같은_순서() {
        assertThat(SentenceOrderHandler.shuffle(8, 7L)).isEqualTo(SentenceOrderHandler.shuffle(8, 7L));
        List<Integer> order = SentenceOrderHandler.shuffle(6, 3L);
        assertThat(IntStream.range(0, 6).filter(i -> order.get(i) == i).count()).isLessThan(6);
    }

    @Test
    void 원문에_없는_문장_어구_누락_긴_어구_중복_어구를_찾는다() {
        AssembledProblem p = handler.assemble(new SentenceOrderHandler.Draft(
                "However, with a small budget, the government could not do so and had to make a new plan.",
                List.of("However", "with a small budget the government could not", "do so", "and had to", "make", "a", "a"),
                "x"), PASSAGE, options, 1L);

        assertThat(handler.validate(p, PASSAGE, options).stream().filter(c -> !c.passed()))
                .extracting(ValidationCheck::name)
                .containsExactlyInAnyOrder("SENTENCE_IN_PASSAGE", "CHUNKS_MATCH_SENTENCE", "CHUNK_LENGTH", "DISTINCT_CHUNKS");
    }

    @Test
    void 따옴표_콜론이_있는_문장은_실패하고_아포스트로피는_괜찮다() {
        PassageSource passage = new PassageSource(3L, null, "The infants responded to the mothers' perfume and the control perfume, 'Cachet', "
                + "which could mean they did not recognize it. The veterinarian's guidance helped us provide water to the thirsty birds quickly.");
        AssembledProblem quoted = handler.assemble(new SentenceOrderHandler.Draft(
                "The infants responded to the mothers' perfume and the control perfume, 'Cachet', which could mean they did not recognize it.",
                List.of("The infants responded", "to the mothers' perfume", "and the control perfume", "Cachet", "which could mean",
                        "they did not recognize it"), "x"), passage, options, 1L);
        AssembledProblem apostrophe = handler.assemble(new SentenceOrderHandler.Draft(
                "The veterinarian's guidance helped us provide water to the thirsty birds quickly.",
                List.of("The veterinarian's guidance", "helped us", "provide water", "to the thirsty birds", "quickly"), "x"),
                passage, options, 1L);

        assertThat(handler.validate(quoted, passage, options).stream().filter(c -> !c.passed()))
                .extracting(ValidationCheck::name).containsExactly("SIMPLE_PUNCTUATION");
        assertThat(handler.validate(apostrophe, passage, options).stream().filter(c -> !c.passed()))
                .extracting(ValidationCheck::name).doesNotContain("SIMPLE_PUNCTUATION");
    }

    @Test
    void 짧은_문장은_실패() {
        AssembledProblem p = handler.assemble(new SentenceOrderHandler.Draft(
                "For years, the neighborhood was known for its high crime rates.",
                List.of("For years", "the neighborhood", "was known", "for", "its high crime rates"), "x"), PASSAGE, options, 1L);

        assertThat(handler.validate(p, PASSAGE, options).stream().filter(c -> !c.passed()))
                .extracting(ValidationCheck::name).containsExactly("MIN_WORDS");
    }

    @Test
    void 어구가_너무_적으면_조립할_수_없다() {
        assertThatThrownBy(() -> handler.assemble(new SentenceOrderHandler.Draft(SENTENCE, List.of("However,", "the rest"), "x"),
                PASSAGE, options, 1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("어구는 5~10개여야 하는데 2개다.");
    }
}
