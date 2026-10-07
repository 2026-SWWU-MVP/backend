package com.smwu.backend.material.dto;

import com.smwu.backend.material.domain.Material;
import com.smwu.backend.material.domain.MaterialSourceType;
import com.smwu.backend.material.domain.MaterialStatus;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 시험범위 자료. PDF는 업로드하면 바로 지문 분리가 시작되므로, 프론트는 status가 SPLIT 또는 FAILED가 될 때까지
 * GET /api/materials/{id}를 폴링한다.
 *
 * @param scanned      스캔본(사진 → PDF)이면 true. 텍스트 자료는 false
 * @param warnings     지문을 나누면서 남긴 경고 (빈칸이 있어 원문을 알 수 없는 지문 등)
 * @param passageCount 나눠진 지문 수
 */
public record MaterialResponse(
        Long id,
        Long workspaceId,
        String title,
        MaterialSourceType sourceType,
        String originalFilename,
        int pageCount,
        boolean scanned,
        MaterialStatus status,
        String failureReason,
        List<String> warnings,
        long passageCount,
        LocalDateTime createdAt,
        Long createdBy,
        String createdByName
) {

    public static MaterialResponse of(Material m, long passageCount, String createdByName) {
        return new MaterialResponse(m.getId(), m.getWorkspaceId(), m.getTitle(), m.getSourceType(), m.getOriginalFilename(),
                m.getPageCount(), m.getSourceType() == MaterialSourceType.PDF && !m.isTextLayer(), m.getStatus(),
                m.getFailureReason(), List.copyOf(m.getWarnings()), passageCount, m.getCreatedAt(), m.getCreatedBy(), createdByName);
    }
}
