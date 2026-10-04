package com.smwu.backend.schooldb.controller;

import com.smwu.backend.schooldb.dto.SchoolTrendResponse;
import com.smwu.backend.schooldb.service.SchoolTrendService;
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
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class SchoolTrendController {

    private final SchoolTrendService trendService;

    @GetMapping("/schools/{schoolId}/trends")
    public SchoolTrendResponse trends(@PathVariable Long schoolId, @RequestParam @Min(1) @Max(3) int grade) {
        return trendService.trends(schoolId, grade);
    }

    /** 워크스페이스 화면용: 내 워크스페이스의 학교·학년으로 조회 */
    @GetMapping("/workspaces/{workspaceId}/school-trends")
    public SchoolTrendResponse workspaceTrends(@PathVariable Long workspaceId) {
        return trendService.forWorkspace(workspaceId);
    }
}
