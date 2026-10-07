package com.smwu.backend.schooldb.controller;

import com.smwu.backend.schooldb.dto.SchoolTrendResponse;
import com.smwu.backend.schooldb.dto.SchoolTrendSummaryResponse;
import com.smwu.backend.schooldb.service.SchoolTrendService;
import com.smwu.backend.schooldb.service.SchoolTrendSummaryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 학교 + 학년 누적 출제 경향 (학교 DB, 원문 없음) */
@Validated
@Tag(name = "06. 학교 DB", description = "여러 학원 기출의 통계만 모은 학교 + 학년 경향 (원문 없음)")
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class SchoolTrendController {

    private final SchoolTrendService trendService;
    private final SchoolTrendSummaryService summaryService;

    @Operation(summary = "학교 누적 경향")
    @GetMapping("/schools/{schoolId}/trends")
    public SchoolTrendResponse trends(@PathVariable Long schoolId, @RequestParam @Min(1) @Max(3) int grade) {
        return trendService.trends(schoolId, grade);
    }

    /** 워크스페이스 화면용: 내 워크스페이스의 학교·학년으로 조회 */
    @Operation(summary = "내 워크스페이스의 학교 경향", description = "워크스페이스 화면용: 내 워크스페이스의 학교·학년으로 조회")
    @GetMapping("/workspaces/{workspaceId}/school-trends")
    public SchoolTrendResponse workspaceTrends(@PathVariable Long workspaceId) {
        return trendService.forWorkspace(workspaceId);
    }

    /** 경향 요약 문장 (LLM). 회차 구성이 바뀌었을 때만 새로 만들어 수 초 걸리고, 그 외에는 캐시 */
    @Operation(summary = "학교 경향 요약 (LLM)", description = "경향 요약 문장 (LLM). 회차 구성이 바뀌었을 때만 새로 만들어 수 초 걸리고, 그 외에는 캐시")
    @GetMapping("/schools/{schoolId}/trends/summary")
    public SchoolTrendSummaryResponse summary(@PathVariable Long schoolId, @RequestParam @Min(1) @Max(3) int grade) {
        return summaryService.summary(schoolId, grade);
    }

    @Operation(summary = "내 워크스페이스의 학교 경향 요약")
    @GetMapping("/workspaces/{workspaceId}/school-trends/summary")
    public SchoolTrendSummaryResponse workspaceSummary(@PathVariable Long workspaceId) {
        return summaryService.forWorkspace(workspaceId);
    }
}
