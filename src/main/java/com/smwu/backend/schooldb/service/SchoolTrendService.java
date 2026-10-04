package com.smwu.backend.schooldb.service;

import com.smwu.backend.schooldb.domain.ExamContribution;
import com.smwu.backend.schooldb.domain.SchoolExam;
import com.smwu.backend.schooldb.dto.SchoolTrendResponse;
import com.smwu.backend.schooldb.repository.ExamContributionRepository;
import com.smwu.backend.schooldb.repository.SchoolExamRepository;
import com.smwu.backend.workspace.domain.School;
import com.smwu.backend.workspace.domain.Workspace;
import com.smwu.backend.workspace.service.SchoolService;
import com.smwu.backend.workspace.service.WorkspaceService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;

/** 학교 + 학년의 누적 경향 조회. 모든 학원이 볼 수 있다 (통계만) */
@Service
@RequiredArgsConstructor
public class SchoolTrendService {

    private final SchoolExamRepository schoolExamRepository;
    private final ExamContributionRepository contributionRepository;
    private final SchoolService schoolService;
    private final WorkspaceService workspaceService;

    @Transactional(readOnly = true)
    public SchoolTrendResponse trends(Long schoolId, int grade) {
        School school = schoolService.getSchool(schoolId);
        List<SchoolExam> rounds = schoolExamRepository.findBySchoolIdAndGrade(schoolId, grade);
        int contributors = rounds.isEmpty() ? 0 : (int) contributionRepository
                .findBySchoolExamIdIn(rounds.stream().map(SchoolExam::getId).toList()).stream()
                .map(ExamContribution::getAcademyId).distinct().count();
        String latest = rounds.stream().max(Comparator.comparingInt(SchoolExam::order))
                .map(SchoolTrendCalculator::label).orElse(null);
        return SchoolTrendResponse.of(schoolId, school.getName(), grade, contributors, latest, SchoolTrendCalculator.calculate(rounds));
    }

    /** 내 워크스페이스(학교 + 학년)의 학교 DB 경향 */
    @Transactional(readOnly = true)
    public SchoolTrendResponse forWorkspace(Long workspaceId) {
        Workspace workspace = workspaceService.getWorkspace(workspaceId);
        return trends(workspace.getSchoolId(), workspace.getGrade());
    }
}
