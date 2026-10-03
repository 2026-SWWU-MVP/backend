package com.smwu.backend.material.service;

import com.smwu.backend.common.exception.BusinessException;
import com.smwu.backend.common.exception.ErrorCode;
import com.smwu.backend.material.domain.Material;
import com.smwu.backend.material.domain.Passage;
import com.smwu.backend.material.dto.PassageRequest;
import com.smwu.backend.material.dto.PassageResponse;
import com.smwu.backend.material.repository.PassageRepository;
import com.smwu.backend.profile.service.ProfileQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** 지문 조회·추가·수정·삭제. 문제 생성(#17)은 {@link #getPassage}로 지문을 가져온다 */
@Service
@RequiredArgsConstructor
public class PassageService {

    private final PassageRepository passageRepository;
    private final MaterialService materialService;
    private final ProfileQueryService profileQueryService;

    @Transactional(readOnly = true)
    public List<PassageResponse> list(Long materialId) {
        materialService.getMaterial(materialId);
        return passageRepository.findByMaterialIdOrderByOrderNoAscIdAsc(materialId).stream().map(PassageResponse::of).toList();
    }

    @Transactional
    public PassageResponse add(Long materialId, PassageRequest request) {
        Material material = materialService.getMaterial(materialId);
        material.requireNotSplitting();
        if (request.content() == null || request.content().isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "지문 본문을 입력해 주세요.");
        }
        int orderNo = request.orderNo() != null ? request.orderNo()
                : passageRepository.findByMaterialIdOrderByOrderNoAscIdAsc(materialId).stream()
                .mapToInt(Passage::getOrderNo).max().orElse(0) + 1;
        Passage passage = new Passage(materialId, material.getWorkspaceId(), orderNo, blankToNull(request.title()),
                blankToNull(request.sourceLabel()), request.content().strip());
        return PassageResponse.of(passageRepository.save(passage));
    }

    @Transactional
    public PassageResponse update(Long passageId, PassageRequest request) {
        Passage passage = getPassage(passageId);
        materialService.getMaterial(passage.getMaterialId()).requireNotSplitting();
        if (request.content() != null && request.content().isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "지문 본문은 비울 수 없습니다.");
        }
        passage.edit(request.title(), request.sourceLabel(), request.content(), request.orderNo());
        return PassageResponse.of(passage);
    }

    @Transactional
    public void delete(Long passageId) {
        Passage passage = getPassage(passageId);
        materialService.getMaterial(passage.getMaterialId()).requireNotSplitting();
        passageRepository.delete(passage);
    }

    public Passage getPassage(Long passageId) {
        Passage passage = passageRepository.findById(passageId).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        profileQueryService.checkWorkspaceAccess(passage.getWorkspaceId());
        return passage;
    }

    private static String blankToNull(String text) {
        return text == null || text.isBlank() ? null : text.strip();
    }
}
