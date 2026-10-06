package com.smwu.backend.material.service;

import com.smwu.backend.common.exception.BusinessException;
import com.smwu.backend.common.exception.ErrorCode;
import com.smwu.backend.common.storage.FileStorage;
import com.smwu.backend.document.PdfUpload;
import com.smwu.backend.material.domain.Material;
import com.smwu.backend.material.domain.MaterialStatus;
import com.smwu.backend.material.domain.Passage;
import com.smwu.backend.material.dto.MaterialResponse;
import com.smwu.backend.material.dto.TextMaterialRequest;
import com.smwu.backend.material.repository.MaterialRepository;
import com.smwu.backend.material.repository.PassageRepository;
import com.smwu.backend.material.service.PassageSplitter.SplitPassage;
import com.smwu.backend.workspace.service.WorkspaceAccessChecker;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 시험범위 자료 업로드·조회·삭제.
 * PDF는 업로드 직후 지문 분리를 백그라운드로 시작하고, 붙여넣은 텍스트는 바로 지문으로 나눠 저장한다.
 */
@Service
@RequiredArgsConstructor
public class MaterialService {

    static final int MAX_PAGES = 40;
    private static final String STORAGE_DIR = "materials";

    private final MaterialRepository materialRepository;
    private final PassageRepository passageRepository;
    private final FileStorage fileStorage;
    private final MaterialSplitRunner splitRunner;
    private final WorkspaceAccessChecker accessChecker;

    @Transactional
    public MaterialResponse uploadPdf(Long workspaceId, MultipartFile file, String title) {
        accessChecker.check(workspaceId);
        PdfUpload.Validated pdf = PdfUpload.validate(file, MAX_PAGES, "시험범위 PDF", "material.pdf");
        String filePath = fileStorage.save(STORAGE_DIR, "pdf", pdf.content());
        String resolvedTitle = title == null || title.isBlank() ? pdf.filename().replaceFirst("(?i)\\.pdf$", "") : title.strip();
        Material material = Material.pdf(workspaceId, truncate(resolvedTitle), filePath, pdf.filename(),
                pdf.info().pageCount(), pdf.info().textLayer());
        material.startSplit();
        materialRepository.save(material);

        Long materialId = material.getId();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                splitRunner.run(materialId);
            }

            @Override
            public void afterCompletion(int status) {
                if (status == STATUS_ROLLED_BACK) {
                    fileStorage.delete(filePath);
                }
            }
        });
        return MaterialResponse.of(material, 0);
    }

    @Transactional
    public MaterialResponse uploadText(Long workspaceId, TextMaterialRequest request) {
        accessChecker.check(workspaceId);
        List<SplitPassage> split = PassageSplitter.splitText(request.text());
        if (split.isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "지문을 입력해 주세요.");
        }
        List<SplitPassage> tooLong = split.stream().filter(p -> p.text().length() > PassageSplitter.MAX_PASSAGE_CHARS).toList();
        if (!tooLong.isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST,
                    "지문 하나는 " + PassageSplitter.MAX_PASSAGE_CHARS + "자 이하입니다. 여러 지문은 --- 줄로 구분해 주세요.");
        }
        Material material = materialRepository.save(Material.text(workspaceId, request.title().strip()));
        for (int i = 0; i < split.size(); i++) {
            SplitPassage p = split.get(i);
            passageRepository.save(new Passage(material.getId(), workspaceId, i + 1, p.title(), p.sourceLabel(), p.text()));
        }
        return MaterialResponse.of(material, split.size());
    }

    @Transactional(readOnly = true)
    public List<MaterialResponse> list(Long workspaceId) {
        accessChecker.check(workspaceId);
        List<Material> materials = materialRepository.findByWorkspaceIdOrderByIdDesc(workspaceId);
        Map<Long, Long> counts = new HashMap<>();
        if (!materials.isEmpty()) {
            passageRepository.countByMaterial(materials.stream().map(Material::getId).toList())
                    .forEach(row -> counts.put((Long) row[0], (Long) row[1]));
        }
        return materials.stream().map(m -> MaterialResponse.of(m, counts.getOrDefault(m.getId(), 0L))).toList();
    }

    @Transactional(readOnly = true)
    public MaterialResponse get(Long materialId) {
        Material material = getMaterial(materialId);
        return MaterialResponse.of(material, passageRepository.countByMaterialId(materialId));
    }

    /** 지문 다시 나누기 (실패했거나 결과가 마음에 들지 않을 때). 기존 지문은 새 결과로 바뀐다 */
    @Transactional
    public MaterialResponse resplit(Long materialId) {
        Material material = getMaterial(materialId);
        material.startSplit();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                splitRunner.run(materialId);
            }
        });
        return MaterialResponse.of(material, passageRepository.countByMaterialId(materialId));
    }

    @Transactional
    public void delete(Long materialId) {
        Material material = getMaterial(materialId);
        material.requireNotSplitting();
        // 이 자료로 만든 문제는 지문 사본을 갖고 있어 그대로 남는다
        passageRepository.deleteByMaterialId(materialId);
        materialRepository.delete(material);
        String filePath = material.getFilePath();
        if (filePath != null) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    fileStorage.delete(filePath);
                }
            });
        }
    }

    public Material getMaterial(Long materialId) {
        Material material = materialRepository.findById(materialId).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        accessChecker.check(material.getWorkspaceId());
        return material;
    }

    private static String truncate(String title) {
        return title.length() > 200 ? title.substring(0, 200) : title;
    }

    static boolean isSplitting(Material material) {
        return material.getStatus() == MaterialStatus.SPLITTING;
    }
}
