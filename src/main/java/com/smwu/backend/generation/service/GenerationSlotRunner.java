package com.smwu.backend.generation.service;

import com.smwu.backend.common.config.AsyncConfig;
import com.smwu.backend.generation.domain.GenerationPlan.Slot;
import com.smwu.backend.problem.service.GenerationContextFactory.ProfileContext;
import com.smwu.backend.problem.service.ProblemGenerationService;
import com.smwu.backend.problem.type.PassageSource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * 생성 작업의 문항 하나를 만든다 (generationExecutor, 동시 4개).
 * 문항 하나가 실패해도 작업 전체는 계속된다. LLM 호출 동안 트랜잭션은 열려 있지 않다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GenerationSlotRunner {

    private final ProblemGenerationService generationService;
    private final GenerationProgress progress;

    @Async(AsyncConfig.GENERATION_EXECUTOR)
    public void run(Long jobId, Slot slot, ProfileContext profileContext, PassageSource passage) {
        boolean error = false;
        try {
            generationService.generate(profileContext, passage, slot.type(), slot.options(), jobId, slot.index(),
                    jobId * 1000 + slot.index());
        } catch (RuntimeException e) {
            error = true;
            log.warn("문제 생성 작업 {} 문항 {} 실패: {}", jobId, slot.index(), e.toString());
        } finally {
            progress.recordFinished(jobId, error);
        }
    }
}
