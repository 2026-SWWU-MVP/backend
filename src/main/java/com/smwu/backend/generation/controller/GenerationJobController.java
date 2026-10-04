package com.smwu.backend.generation.controller;

import com.smwu.backend.generation.dto.CreateGenerationJobRequest;
import com.smwu.backend.generation.dto.GenerationJobResponse;
import com.smwu.backend.generation.service.GenerationJobService;
import com.smwu.backend.problem.dto.ProblemResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 문제 생성 작업.
 * <ol>
 *   <li>POST /api/workspaces/{id}/generation-jobs → 202 (status=RUNNING)</li>
 *   <li>GET /api/generation-jobs/{id} 를 2초 간격으로 폴링 (progress, passed / needsReview / failed)</li>
 *   <li>status가 COMPLETED 또는 FAILED가 되면 GET /api/generation-jobs/{id}/problems 로 문항 조회</li>
 * </ol>
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class GenerationJobController {

    private final GenerationJobService jobService;

    @PostMapping("/workspaces/{workspaceId}/generation-jobs")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public GenerationJobResponse create(@PathVariable Long workspaceId, @Valid @RequestBody CreateGenerationJobRequest request) {
        return jobService.create(workspaceId, request);
    }

    @GetMapping("/workspaces/{workspaceId}/generation-jobs")
    public List<GenerationJobResponse> list(@PathVariable Long workspaceId) {
        return jobService.list(workspaceId);
    }

    @GetMapping("/generation-jobs/{jobId}")
    public GenerationJobResponse get(@PathVariable Long jobId) {
        return jobService.get(jobId);
    }

    /** 생성된 문항 (지문 순서 → 유형 순서). 진행 중에도 끝난 문항까지 조회할 수 있다 */
    @GetMapping("/generation-jobs/{jobId}/problems")
    public List<ProblemResponse> problems(@PathVariable Long jobId) {
        return jobService.problems(jobId);
    }
}
