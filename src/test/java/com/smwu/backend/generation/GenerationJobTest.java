package com.smwu.backend.generation;

import com.smwu.backend.generation.domain.GenerationJob;
import com.smwu.backend.generation.domain.GenerationJobStatus;
import com.smwu.backend.generation.domain.GenerationPlan;
import com.smwu.backend.generation.domain.GenerationPlan.Slot;
import com.smwu.backend.generation.domain.GenerationPlan.TypeCount;
import com.smwu.backend.pastexam.extraction.QuestionType;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GenerationJobTest {

    private static final GenerationPlan PLAN = new GenerationPlan(List.of(10L, 20L), List.of(
            new TypeCount(QuestionType.SUMMARY_BLANK, 2, null),
            new TypeCount(QuestionType.SENTENCE_ORDER, 1, null)));

    @Test
    void 문항_순서는_지문_순서_유형_순서_순번() {
        assertThat(PLAN.total()).isEqualTo(6);
        assertThat(PLAN.slots()).extracting(Slot::index, Slot::passageId, Slot::type).containsExactly(
                org.assertj.core.groups.Tuple.tuple(0, 10L, QuestionType.SUMMARY_BLANK),
                org.assertj.core.groups.Tuple.tuple(1, 10L, QuestionType.SUMMARY_BLANK),
                org.assertj.core.groups.Tuple.tuple(2, 10L, QuestionType.SENTENCE_ORDER),
                org.assertj.core.groups.Tuple.tuple(3, 20L, QuestionType.SUMMARY_BLANK),
                org.assertj.core.groups.Tuple.tuple(4, 20L, QuestionType.SUMMARY_BLANK),
                org.assertj.core.groups.Tuple.tuple(5, 20L, QuestionType.SENTENCE_ORDER));
    }

    @Test
    void 모두_끝나야_종료되고_전부_오류면_FAILED() {
        GenerationJob job = GenerationJob.start(1L, 2L, PLAN);
        ReflectionTestUtils.setField(job, "completed", 5);
        job.finishIfDone();
        assertThat(job.getStatus()).isEqualTo(GenerationJobStatus.RUNNING);
        assertThat(job.progressPercent()).isEqualTo(83);

        ReflectionTestUtils.setField(job, "completed", 6);
        ReflectionTestUtils.setField(job, "errors", 2);
        job.finishIfDone();
        assertThat(job.getStatus()).isEqualTo(GenerationJobStatus.COMPLETED);
        assertThat(job.getFinishedAt()).isNotNull();

        GenerationJob allFailed = GenerationJob.start(1L, 2L, PLAN);
        ReflectionTestUtils.setField(allFailed, "completed", 6);
        ReflectionTestUtils.setField(allFailed, "errors", 6);
        allFailed.finishIfDone();
        assertThat(allFailed.getStatus()).isEqualTo(GenerationJobStatus.FAILED);
        assertThat(allFailed.getFailureReason()).isNotNull();
    }
}
