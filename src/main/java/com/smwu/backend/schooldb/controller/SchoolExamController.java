package com.smwu.backend.schooldb.controller;

import com.smwu.backend.schooldb.domain.SchoolExam;
import com.smwu.backend.schooldb.dto.SchoolExamResponse;
import com.smwu.backend.schooldb.repository.SchoolExamRepository;
import com.smwu.backend.workspace.service.SchoolService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Comparator;
import java.util.List;

/** 학교 DB 조회. 모든 학원이 볼 수 있고, 통계 숫자만 나간다 */
@Validated
@Tag(name = "06. 학교 DB", description = "여러 학원 기출의 통계만 모은 학교 + 학년 경향 (원문 없음)")
@RestController
@RequestMapping("/api/schools/{schoolId}")
@RequiredArgsConstructor
public class SchoolExamController {

    private final SchoolExamRepository schoolExamRepository;
    private final SchoolService schoolService;

    /** 학교 + 학년의 기출 회차 (최신순) */
    @Operation(summary = "학교 DB 기출 회차", description = "학교 + 학년의 기출 회차 (최신순)")
    @GetMapping("/exams")
    @Transactional(readOnly = true)
    public List<SchoolExamResponse> exams(@PathVariable Long schoolId,
                                          @RequestParam @Min(1) @Max(3) int grade) {
        schoolService.getSchool(schoolId);
        return schoolExamRepository.findBySchoolIdAndGrade(schoolId, grade).stream()
                .sorted(Comparator.comparingInt(SchoolExam::order).reversed())
                .map(SchoolExamResponse::of)
                .toList();
    }
}
