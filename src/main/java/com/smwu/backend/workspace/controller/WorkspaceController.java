package com.smwu.backend.workspace.controller;

import com.smwu.backend.workspace.dto.WorkspaceRequest;
import com.smwu.backend.workspace.dto.WorkspaceResponse;
import com.smwu.backend.workspace.service.WorkspaceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 워크스페이스(학교 + 학년) 카드 목록과 생성·삭제 */
@Tag(name = "03. 학교·워크스페이스", description = "공용 학교 목록과 학원의 워크스페이스(학교 + 학년)")
@RestController
@RequestMapping("/api/workspaces")
@RequiredArgsConstructor
public class WorkspaceController {

    private final WorkspaceService workspaceService;

    @Operation(summary = "워크스페이스 카드 목록")
    @GetMapping
    public List<WorkspaceResponse> list() {
        return workspaceService.list();
    }

    /** 같은 학교 + 학년이 이미 있으면 409 WORKSPACE_DUPLICATED */
    @Operation(summary = "워크스페이스 추가", description = "같은 학교 + 학년이 이미 있으면 409 WORKSPACE_DUPLICATED")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public WorkspaceResponse create(@Valid @RequestBody WorkspaceRequest request) {
        return workspaceService.create(request);
    }

    @Operation(summary = "워크스페이스 상세")
    @GetMapping("/{workspaceId}")
    public WorkspaceResponse get(@PathVariable Long workspaceId) {
        return workspaceService.get(workspaceId);
    }

    /** 비어 있는 워크스페이스만 삭제 (기출·시험범위·프로필이 있으면 409) */
    @Operation(summary = "🔒 워크스페이스 삭제", description = "비어 있는 워크스페이스만 삭제 (기출·시험범위·프로필이 있으면 409)")
    @DeleteMapping("/{workspaceId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long workspaceId) {
        workspaceService.delete(workspaceId);
    }
}
