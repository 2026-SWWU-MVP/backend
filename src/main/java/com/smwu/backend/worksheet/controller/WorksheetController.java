package com.smwu.backend.worksheet.controller;

import com.smwu.backend.worksheet.dto.WorksheetDtos.CreateWorksheetRequest;
import com.smwu.backend.worksheet.dto.WorksheetDtos.UpdateWorksheetRequest;
import com.smwu.backend.worksheet.dto.WorksheetDtos.WorksheetResponse;
import com.smwu.backend.worksheet.dto.WorksheetDtos.WorksheetSummary;
import com.smwu.backend.worksheet.service.WorksheetService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 시험지 구성 (#16). PDF 출력은 #18 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class WorksheetController {

    private final WorksheetService worksheetService;

    /** 채택한 문항으로 시험지 만들기. 채택 안 된 문항·다른 워크스페이스 문항·중복은 400 */
    @PostMapping("/workspaces/{workspaceId}/worksheets")
    @ResponseStatus(HttpStatus.CREATED)
    public WorksheetResponse create(@PathVariable Long workspaceId, @Valid @RequestBody CreateWorksheetRequest request) {
        return worksheetService.create(workspaceId, request);
    }

    /** 시험지 목록 (최신순, 작성자 포함, 학원 내 공유) */
    @GetMapping("/workspaces/{workspaceId}/worksheets")
    public List<WorksheetSummary> list(@PathVariable Long workspaceId) {
        return worksheetService.list(workspaceId);
    }

    /** 시험지 본문 (미리보기): 지문 단위 묶음과 문항 */
    @GetMapping("/worksheets/{worksheetId}")
    public WorksheetResponse get(@PathVariable Long worksheetId) {
        return worksheetService.get(worksheetId);
    }

    /** 제목·머리글·로고 표시·문항 구성 수정 */
    @PatchMapping("/worksheets/{worksheetId}")
    public WorksheetResponse update(@PathVariable Long worksheetId, @Valid @RequestBody UpdateWorksheetRequest request) {
        return worksheetService.update(worksheetId, request);
    }

    @DeleteMapping("/worksheets/{worksheetId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long worksheetId) {
        worksheetService.delete(worksheetId);
    }
}
