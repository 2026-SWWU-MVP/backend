package com.smwu.backend.problem.controller;

import com.smwu.backend.problem.dto.ProblemResponse;
import com.smwu.backend.problem.service.ProblemGenerationService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 생성 문항 조회와 개별 재생성. 검수(수정·채택·폐기)는 #16.
 */
@RestController
@RequestMapping("/api/problems")
@RequiredArgsConstructor
public class ProblemController {

    private final ProblemGenerationService generationService;

    @GetMapping("/{problemId}")
    public ProblemResponse get(@PathVariable Long problemId) {
        return ProblemResponse.of(generationService.getProblem(problemId));
    }

    /** 같은 지문·유형·옵션으로 다시 생성해 이 문항을 덮어쓴다 (검수 상태 초기화). 수 초~수십 초 걸린다 */
    @PostMapping("/{problemId}/regenerate")
    public ProblemResponse regenerate(@PathVariable Long problemId) {
        return ProblemResponse.of(generationService.regenerate(problemId));
    }
}
