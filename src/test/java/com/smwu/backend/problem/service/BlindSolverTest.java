package com.smwu.backend.problem.service;

import com.smwu.backend.problem.service.BlindSolver.BlankSolution;
import com.smwu.backend.problem.service.BlindSolver.SummaryBlankSolution;
import com.smwu.backend.problem.type.PassageSource;
import com.smwu.backend.problem.type.ProblemOptions;
import com.smwu.backend.problem.type.ValidationCheck;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BlindSolverTest {

    private static final PassageSource PASSAGE = new PassageSource(null, null,
            "As cities age, neighborhoods can become old and lifeless, which may cause citizens to move away. "
                    + "A collaboration between the local government and residents is an effective way to revitalize the area.");

    @Test
    void 답이_같고_다른_후보가_없으면_통과() {
        List<ValidationCheck> checks = BlindSolver.compareSummaryBlank(List.of("lifeless", "revitalize"),
                new SummaryBlankSolution(List.of(new BlankSolution("Lifeless ", List.of()), new BlankSolution("revitalize", List.of("revitalize")))),
                PASSAGE, ProblemOptions.defaults());

        assertThat(checks).allMatch(ValidationCheck::passed);
    }

    @Test
    void 형태_변경_없음_조건이면_윗글에_있는_후보만_다른_정답으로_본다() {
        List<ValidationCheck> checks = BlindSolver.compareSummaryBlank(List.of("citizens"),
                new SummaryBlankSolution(List.of(new BlankSolution("citizens", List.of("residents", "people", "two words")))),
                PASSAGE, new ProblemOptions(1, false, null).withDefaults());

        assertThat(checks).filteredOn(c -> !c.passed()).singleElement()
                .satisfies(c -> assertThat(c.detail()).isEqualTo("정답이 하나로 정해지지 않을 수 있다: (1) citizens 외에 residents도 정답이 될 수 있다"));
    }

    @Test
    void 첫_철자_조건이면_같은_글자로_시작하는_후보만_본다() {
        List<ValidationCheck> checks = BlindSolver.compareSummaryBlank(List.of("restore"),
                new SummaryBlankSolution(List.of(new BlankSolution("restore", List.of("revive", "renew", "save")))),
                PASSAGE, new ProblemOptions(1, true, null).withDefaults());

        assertThat(checks).filteredOn(c -> !c.passed()).singleElement()
                .satisfies(c -> assertThat(c.detail()).contains("revive, renew").doesNotContain("save"));
    }

    @Test
    void 빈칸_수가_다르면_실패() {
        assertThat(BlindSolver.compareSummaryBlank(List.of("a", "b"),
                new SummaryBlankSolution(List.of(new BlankSolution("a", List.of()))), PASSAGE, ProblemOptions.defaults()))
                .singleElement().satisfies(c -> assertThat(c.passed()).isFalse());
    }
}
