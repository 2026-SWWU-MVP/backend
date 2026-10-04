package com.smwu.backend.schooldb.service;

import com.smwu.backend.schooldb.domain.ExamContribution;
import com.smwu.backend.schooldb.domain.SchoolExam;
import com.smwu.backend.schooldb.dto.SchoolTrendResponse;
import com.smwu.backend.schooldb.repository.ExamContributionRepository;
import com.smwu.backend.schooldb.repository.SchoolExamRepository;
import com.smwu.backend.schooldb.service.SchoolTrendCalculator.Trend;
import com.smwu.backend.workspace.domain.School;
import com.smwu.backend.workspace.domain.Workspace;
import com.smwu.backend.workspace.service.SchoolService;
import com.smwu.backend.workspace.service.WorkspaceService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** 학교 + 학년의 누적 경향 조회. 모든 학원이 볼 수 있다 (통계만) */
@Service
@RequiredArgsConstructor
public class SchoolTrendService {

    private final SchoolExamRepository schoolExamRepository;
    private final ExamContributionRepository contributionRepository;
    private final SchoolService schoolService;
    private final WorkspaceService workspaceService;

    /**
     * 한 시점의 학교 DB 상태 (요약·프로필에서도 쓴다).
     *
     * @param contributorsByRound 회차별 기여 학원 수
     * @param contributorCount    전체 회차의 서로 다른 기여 학원 수
     */
    public record Snapshot(School school, int grade, List<SchoolExam> rounds, Map<Long, Integer> contributorsByRound,
                           int contributorCount, String latestExam, Trend trend) {

        public String target() {
            return school.getName() + " " + grade + "학년";
        }
    }

    @Transactional(readOnly = true)
    public Snapshot snapshot(Long schoolId, int grade) {
        School school = schoolService.getSchool(schoolId);
        List<SchoolExam> rounds = schoolExamRepository.findBySchoolIdAndGrade(schoolId, grade);
        List<ExamContribution> contributions = rounds.isEmpty() ? List.of()
                : contributionRepository.findBySchoolExamIdIn(rounds.stream().map(SchoolExam::getId).toList());
        Map<Long, Integer> byRound = contributions.stream().collect(Collectors.groupingBy(ExamContribution::getSchoolExamId,
                Collectors.collectingAndThen(Collectors.mapping(ExamContribution::getAcademyId, Collectors.toSet()), s -> s.size())));
        int contributors = (int) contributions.stream().map(ExamContribution::getAcademyId).distinct().count();
        String latest = rounds.stream().max(Comparator.comparingInt(SchoolExam::order)).map(SchoolTrendCalculator::label).orElse(null);
        return new Snapshot(school, grade, rounds, byRound, contributors, latest, SchoolTrendCalculator.calculate(rounds));
    }

    @Transactional(readOnly = true)
    public Snapshot snapshotForWorkspace(Long workspaceId) {
        Workspace workspace = workspaceService.getWorkspace(workspaceId);
        return snapshot(workspace.getSchoolId(), workspace.getGrade());
    }

    public SchoolTrendResponse trends(Long schoolId, int grade) {
        return toResponse(snapshot(schoolId, grade));
    }

    /** 내 워크스페이스(학교 + 학년)의 학교 DB 경향 */
    public SchoolTrendResponse forWorkspace(Long workspaceId) {
        return toResponse(snapshotForWorkspace(workspaceId));
    }

    private static SchoolTrendResponse toResponse(Snapshot s) {
        return SchoolTrendResponse.of(s.school().getId(), s.school().getName(), s.grade(), s.contributorCount(), s.latestExam(), s.trend());
    }
}
