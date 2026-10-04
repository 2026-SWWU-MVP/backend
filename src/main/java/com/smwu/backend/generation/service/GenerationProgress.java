package com.smwu.backend.generation.service;

import com.smwu.backend.generation.domain.GenerationJobStatus;
import com.smwu.backend.generation.repository.GenerationJobRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** 생성 작업 진행 기록 (문항 하나가 끝날 때마다 짧은 트랜잭션) */
@Slf4j
@Component
@RequiredArgsConstructor
public class GenerationProgress {

    static final String INTERRUPTED_REASON = "서버가 재시작되어 문제 생성이 중단되었습니다. 만들어진 문항은 그대로 남아 있습니다.";

    private final GenerationJobRepository jobRepository;

    /** 끝난 문항 수를 DB에서 1 늘리고, 마지막 문항이면 작업 상태를 정한다 */
    @Transactional
    public void recordFinished(Long jobId, boolean error) {
        jobRepository.increment(jobId, error ? 1 : 0);
        jobRepository.findById(jobId).ifPresent(job -> {
            job.finishIfDone();
            if (job.getStatus() != GenerationJobStatus.RUNNING) {
                log.info("문제 생성 작업 {} 종료: {} ({}문항, 오류 {})", jobId, job.getStatus(), job.getTotal(), job.getErrors());
            }
        });
    }

    /** 서버가 생성 중에 꺼지면 RUNNING에서 멈추므로 시작할 때 실패로 바꾼다 */
    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void failInterrupted() {
        int count = jobRepository.failInterrupted(GenerationJobStatus.RUNNING, GenerationJobStatus.FAILED, INTERRUPTED_REASON);
        if (count > 0) {
            log.warn("중단된 문제 생성 작업 {}건을 FAILED로 변경", count);
        }
    }
}
