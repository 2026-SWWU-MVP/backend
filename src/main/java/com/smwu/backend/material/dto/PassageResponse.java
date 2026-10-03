package com.smwu.backend.material.dto;

import com.smwu.backend.material.domain.Passage;
import com.smwu.backend.problem.type.TextNormalizer;

/** @param wordCount 영단어 수 (문제 생성에 충분한 길이인지 화면에서 안내할 때 사용) */
public record PassageResponse(
        Long id,
        Long materialId,
        int orderNo,
        String title,
        String sourceLabel,
        String content,
        int wordCount,
        boolean edited
) {

    public static PassageResponse of(Passage p) {
        return new PassageResponse(p.getId(), p.getMaterialId(), p.getOrderNo(), p.getTitle(), p.getSourceLabel(),
                p.getContent(), TextNormalizer.wordCount(p.getContent()), p.isEdited());
    }
}
