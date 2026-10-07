package com.smwu.backend.problem.controller;

import com.smwu.backend.problem.domain.ReviewStatus;
import com.smwu.backend.problem.dto.ProblemResponse;
import com.smwu.backend.problem.dto.ReviewStatsResponse;
import com.smwu.backend.problem.dto.UpdateProblemRequest;
import com.smwu.backend.problem.service.ProblemGenerationService;
import com.smwu.backend.problem.service.ProblemReviewService;
import com.smwu.backend.problem.service.ReviewStatsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 생성 문항 조회, 개별 재생성, 검수(수정·채택·폐기) */
@Tag(name = "09. 문항 검수", description = "생성 문항 조회·재생성·수정·채택·폐기")
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class ProblemController {

    private final ProblemGenerationService generationService;
    private final ProblemReviewService reviewService;
    private final ReviewStatsService reviewStatsService;

    @Operation(summary = "문항 상세")
    @GetMapping("/problems/{problemId}")
    public ProblemResponse get(@PathVariable Long problemId) {
        return ProblemResponse.of(generationService.getProblem(problemId));
    }

    /** 같은 지문·유형·옵션으로 다시 생성해 이 문항을 덮어쓴다 (검수 상태 초기화). 수 초~수십 초 걸린다 */
    @Operation(summary = "문항 다시 생성", description = "같은 지문·유형·옵션으로 다시 생성해 이 문항을 덮어쓴다 (검수 상태 초기화). 수 초~수십 초 걸린다")
    @PostMapping("/problems/{problemId}/regenerate")
    public ProblemResponse regenerate(@PathVariable Long problemId) {
        return ProblemResponse.of(generationService.regenerate(problemId));
    }

    /** 수정·채택·폐기. 보낸 항목만 바뀐다. 내용을 고치면 edited=true */
    @Operation(summary = "문항 수정·채택·폐기", description = "수정·채택·폐기. 보낸 항목만 바뀐다. 내용을 고치면 edited=true")
    @PatchMapping("/problems/{problemId}")
    public ProblemResponse update(@PathVariable Long problemId, @Valid @RequestBody UpdateProblemRequest request) {
        return reviewService.update(problemId, request);
    }

    /** 워크스페이스 문항 목록 (최신순). 시험지 구성 화면은 reviewStatus=ACCEPTED */
    @Operation(summary = "검수 통계 (채택률)", description = "유형별·생성 작업별 채택·폐기·수정 수와 채택률. 생성 작업이 거듭될수록 채택률이 오르는지 본다 (#42)")
    @GetMapping("/workspaces/{workspaceId}/review-stats")
    public ReviewStatsResponse reviewStats(@PathVariable Long workspaceId) {
        return reviewStatsService.stats(workspaceId);
    }

    @Operation(summary = "워크스페이스 문항 목록", description = "워크스페이스 문항 목록 (최신순). 시험지 구성 화면은 reviewStatus=ACCEPTED")
    @GetMapping("/workspaces/{workspaceId}/problems")
    public List<ProblemResponse> list(@PathVariable Long workspaceId, @RequestParam(required = false) ReviewStatus reviewStatus) {
        return reviewService.list(workspaceId, reviewStatus);
    }
}
