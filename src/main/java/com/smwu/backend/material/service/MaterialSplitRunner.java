package com.smwu.backend.material.service;

import com.smwu.backend.ai.LlmException;
import com.smwu.backend.ai.LlmResult;
import com.smwu.backend.common.config.AsyncConfig;
import com.smwu.backend.common.storage.FileStorage;
import com.smwu.backend.material.domain.Material;
import com.smwu.backend.material.domain.MaterialStatus;
import com.smwu.backend.material.repository.MaterialRepository;
import com.smwu.backend.material.service.PassageSplitter.SplitResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * PDF 지문 분리 백그라운드 작업. LLM 호출 동안 트랜잭션을 열지 않고, 결과 저장만 {@link MaterialResultWriter}에서 한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MaterialSplitRunner {

    static final String INTERRUPTED_REASON = "서버가 재시작되어 지문 분리가 중단되었습니다. 다시 나눠 주세요.";

    private final MaterialRepository materialRepository;
    private final FileStorage fileStorage;
    private final PassageSplitter splitter;
    private final MaterialResultWriter resultWriter;

    @Async(AsyncConfig.EXTRACTION_EXECUTOR)
    public void run(Long materialId) {
        Material material = materialRepository.findById(materialId).orElse(null);
        if (material == null || material.getFilePath() == null) {
            return;
        }
        try {
            LlmResult<SplitResult> result = splitter.splitPdf(material.getOriginalFilename(), fileStorage.read(material.getFilePath()));
            resultWriter.saveResult(materialId, result);
            log.info("지문 분리 완료 materialId={} 지문 {}개", materialId, result.value().passages().size());
        } catch (LlmException e) {
            log.warn("지문 분리 실패 materialId={}: {}", materialId, e.getDetail());
            resultWriter.markFailed(materialId, "AI가 자료를 읽지 못했습니다. 잠시 후 다시 나눠 주세요.");
        } catch (RuntimeException e) {
            log.error("지문 분리 중 오류 materialId={}", materialId, e);
            resultWriter.markFailed(materialId, "지문을 나누는 중 오류가 발생했습니다. 다시 나눠 주세요.");
        }
    }

    /** 서버가 분리 중에 꺼지면 SPLITTING에서 멈추므로 시작할 때 실패로 바꾼다 */
    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void failInterrupted() {
        int count = materialRepository.failInterrupted(MaterialStatus.SPLITTING, MaterialStatus.FAILED, INTERRUPTED_REASON);
        if (count > 0) {
            log.warn("중단된 지문 분리 {}건을 FAILED로 변경", count);
        }
    }
}
