package com.smwu.backend.material.controller;

import com.smwu.backend.material.dto.MaterialResponse;
import com.smwu.backend.material.dto.PassageRequest;
import com.smwu.backend.material.dto.PassageResponse;
import com.smwu.backend.material.dto.TextMaterialRequest;
import com.smwu.backend.material.service.MaterialService;
import com.smwu.backend.material.service.PassageService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 시험범위 자료와 지문.
 * <ul>
 *   <li>PDF: 업로드 → 202, 백그라운드로 지문 분리 (status: SPLITTING → SPLIT/FAILED, GET /api/materials/{id} 폴링)</li>
 *   <li>텍스트: 붙여넣기 → 201, 바로 지문으로 나뉨 ("---" 줄로 구분)</li>
 * </ul>
 */
@Tag(name = "07. 시험범위 자료·지문", description = "시험범위 PDF·텍스트 → 지문 분리 → 지문 편집")
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class MaterialController {

    private final MaterialService materialService;
    private final PassageService passageService;

    @Operation(summary = "시험범위 PDF 업로드 (지문 분리 시작)")
    @PostMapping(value = "/workspaces/{workspaceId}/materials", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.ACCEPTED)
    public MaterialResponse uploadPdf(@PathVariable Long workspaceId,
                                      @RequestPart("file") MultipartFile file,
                                      @RequestParam(value = "title", required = false) String title) {
        return materialService.uploadPdf(workspaceId, file, title);
    }

    @Operation(summary = "시험범위 텍스트 붙여넣기")
    @PostMapping(value = "/workspaces/{workspaceId}/materials/text", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public MaterialResponse uploadText(@PathVariable Long workspaceId, @Valid @RequestBody TextMaterialRequest request) {
        return materialService.uploadText(workspaceId, request);
    }

    @Operation(summary = "자료 목록")
    @GetMapping("/workspaces/{workspaceId}/materials")
    public List<MaterialResponse> list(@PathVariable Long workspaceId) {
        return materialService.list(workspaceId);
    }

    @Operation(summary = "자료 상세 (분리 상태 폴링)")
    @GetMapping("/materials/{materialId}")
    public MaterialResponse get(@PathVariable Long materialId) {
        return materialService.get(materialId);
    }

    /** PDF 지문 다시 나누기 → 202 (기존 지문은 새 결과로 바뀜) */
    @Operation(summary = "지문 다시 나누기", description = "PDF 지문 다시 나누기 → 202 (기존 지문은 새 결과로 바뀜)")
    @PostMapping("/materials/{materialId}/split")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public MaterialResponse resplit(@PathVariable Long materialId) {
        return materialService.resplit(materialId);
    }

    @Operation(summary = "자료 삭제")
    @DeleteMapping("/materials/{materialId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long materialId) {
        materialService.delete(materialId);
    }

    @Operation(summary = "지문 목록")
    @GetMapping("/materials/{materialId}/passages")
    public List<PassageResponse> passages(@PathVariable Long materialId) {
        return passageService.list(materialId);
    }

    /** 지문 직접 추가 (분리에서 빠진 지문 등) */
    @Operation(summary = "지문 직접 추가", description = "지문 직접 추가 (분리에서 빠진 지문 등)")
    @PostMapping("/materials/{materialId}/passages")
    @ResponseStatus(HttpStatus.CREATED)
    public PassageResponse addPassage(@PathVariable Long materialId, @Valid @RequestBody PassageRequest request) {
        return passageService.add(materialId, request);
    }

    @Operation(summary = "지문 수정")
    @PatchMapping("/passages/{passageId}")
    public PassageResponse updatePassage(@PathVariable Long passageId, @Valid @RequestBody PassageRequest request) {
        return passageService.update(passageId, request);
    }

    @Operation(summary = "지문 삭제")
    @DeleteMapping("/passages/{passageId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deletePassage(@PathVariable Long passageId) {
        passageService.delete(passageId);
    }
}
