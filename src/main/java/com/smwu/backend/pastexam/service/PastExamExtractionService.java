package com.smwu.backend.pastexam.service;

import com.smwu.backend.pastexam.domain.PastExam;
import com.smwu.backend.pastexam.domain.PastExamStatus;
import com.smwu.backend.pastexam.dto.PastExamResponse;
import com.smwu.backend.pastexam.repository.PastExamRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 기출 추출 요청. 추출은 1~2분 걸리므로 상태만 EXTRACTING으로 바꾸고 바로 응답한 뒤,
 * 커밋이 끝나면 {@link PastExamExtractionRunner}가 백그라운드에서 추출한다.
 * 프론트는 GET /api/past-exams/{id}로 status가 EXTRACTED 또는 FAILED가 될 때까지 폴링한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PastExamExtractionService {

    static final String INTERRUPTED_REASON = "서버가 재시작되어 추출이 중단되었습니다. 다시 추출해 주세요.";

    private final PastExamService pastExamService;
    private final PastExamRepository pastExamRepository;
    private final PastExamExtractionRunner extractionRunner;

    @Transactional
    public PastExamResponse requestExtraction(Long examId) {
        PastExam exam = pastExamService.getExam(examId);
        exam.startExtraction();

        // 커밋 전에 백그라운드 작업이 시작되면 EXTRACTING 상태를 못 볼 수 있어 커밋 후 시작
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                extractionRunner.run(examId);
            }
        });
        return PastExamResponse.of(exam, 0, 0);
    }

    /** 서버가 추출 중에 꺼지면 EXTRACTING에서 멈추므로, 시작할 때 실패로 바꿔 다시 추출할 수 있게 한다 */
    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void failInterruptedExtractions() {
        int count = pastExamRepository.failInterrupted(PastExamStatus.EXTRACTING, PastExamStatus.FAILED, INTERRUPTED_REASON);
        if (count > 0) {
            log.warn("중단된 기출 추출 {}건을 FAILED로 변경", count);
        }
    }
}
