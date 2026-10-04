package com.smwu.backend.generation.service;

import com.smwu.backend.common.config.AsyncConfig;
import com.smwu.backend.generation.domain.GenerationPlan.Slot;
import com.smwu.backend.problem.domain.Problem;
import com.smwu.backend.problem.service.GenerationContextFactory.ProfileContext;
import com.smwu.backend.problem.service.ProblemGenerationService;
import com.smwu.backend.problem.type.AssembledProblem;
import com.smwu.backend.problem.type.PassageSource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 생성 작업에서 같은 지문·같은 유형 문항 묶음을 차례로 만든다 (generationExecutor, 동시 4묶음).
 * 묶음 안에서는 앞서 만든 문항을 넘겨 같은 문장·정답이 다시 나오지 않게 한다.
 * 문항 하나가 실패해도 작업 전체는 계속된다. LLM 호출 동안 트랜잭션은 열려 있지 않다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GenerationSlotRunner {

    private final ProblemGenerationService generationService;
    private final GenerationProgress progress;

    @Async(AsyncConfig.GENERATION_EXECUTOR)
    public void run(Long jobId, List<Slot> slots, ProfileContext profileContext, PassageSource passage) {
        List<AssembledProblem> made = new ArrayList<>();
        for (Slot slot : slots) {
            boolean error = false;
            try {
                Problem problem = generationService.generate(profileContext, passage, slot.type(), slot.options(), jobId,
                        slot.index(), jobId * 1000 + slot.index(), List.copyOf(made));
                if (problem.getAnswerText() != null) {
                    made.add(problem.toAssembled());
                }
            } catch (RuntimeException e) {
                error = true;
                log.warn("문제 생성 작업 {} 문항 {} 실패: {}", jobId, slot.index(), e.toString());
            } finally {
                progress.recordFinished(jobId, error);
            }
        }
    }
}
