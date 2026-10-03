package com.smwu.backend.material.service;

import com.smwu.backend.ai.LlmResult;
import com.smwu.backend.material.domain.Material;
import com.smwu.backend.material.domain.Passage;
import com.smwu.backend.material.repository.MaterialRepository;
import com.smwu.backend.material.repository.PassageRepository;
import com.smwu.backend.material.service.PassageSplitter.SplitPassage;
import com.smwu.backend.material.service.PassageSplitter.SplitResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** 지문 분리 결과 저장. 다시 나누면 기존 지문(수정한 것 포함)은 새 결과로 바뀐다 */
@Component
@RequiredArgsConstructor
public class MaterialResultWriter {

    private final MaterialRepository materialRepository;
    private final PassageRepository passageRepository;

    @Transactional
    public void saveResult(Long materialId, LlmResult<SplitResult> result) {
        Material material = materialRepository.findById(materialId).orElse(null);
        if (material == null) {
            return;
        }
        passageRepository.deleteByMaterialId(materialId);
        List<SplitPassage> passages = result.value().passages();
        for (int i = 0; i < passages.size(); i++) {
            SplitPassage p = passages.get(i);
            passageRepository.save(new Passage(materialId, material.getWorkspaceId(), i + 1, p.title(), p.sourceLabel(), p.text()));
        }
        material.completeSplit(result.value().warnings(), result.model());
    }

    @Transactional
    public void markFailed(Long materialId, String reason) {
        materialRepository.findById(materialId).ifPresent(m -> m.failSplit(reason));
    }
}
