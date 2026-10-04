package com.smwu.backend.schooldb.service;

import com.smwu.backend.pastexam.extraction.QuestionType;
import com.smwu.backend.profile.domain.ProfileStats;
import com.smwu.backend.schooldb.domain.ExamContribution;
import com.smwu.backend.schooldb.domain.ExamRoundStats;
import com.smwu.backend.schooldb.domain.SchoolExam;
import com.smwu.backend.schooldb.repository.ExamContributionRepository;
import com.smwu.backend.schooldb.repository.SchoolExamRepository;
import com.smwu.backend.schooldb.service.SchoolTrendCalculator.Trend;
import com.smwu.backend.workspace.domain.Workspace;
import com.smwu.backend.workspace.repository.WorkspaceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 출제 프로필(#41)에 넣을 학교 DB 경향. 내 기출이 기여한 회차는 빼고(같은 회차는 한 번만), 다른 학원 기출 회차만 쓴다.
 */
@Component
@RequiredArgsConstructor
public class SchoolDbProfileSource {

    private final WorkspaceRepository workspaceRepository;
    private final SchoolExamRepository schoolExamRepository;
    private final ExamContributionRepository contributionRepository;

    /**
     * @param rounds           내 기출과 겹치지 않는 학교 DB 회차
     * @param contributorCount 그 회차들을 올린 학원 수
     * @param description      규칙 요약 프롬프트에 넣을 경향 설명 (통계만)
     */
    public record Input(Long schoolId, int grade, List<SchoolExam> rounds, int contributorCount, Trend trend, String basis,
                        String description) {
    }

    @Transactional(readOnly = true)
    public Optional<Input> load(Long workspaceId, Collection<Long> ownPastExamIds) {
        Optional<Workspace> workspace = workspaceRepository.findById(workspaceId);
        if (workspace.isEmpty()) {
            return Optional.empty();
        }
        Workspace w = workspace.get();
        List<SchoolExam> all = schoolExamRepository.findBySchoolIdAndGrade(w.getSchoolId(), w.getGrade());
        if (all.isEmpty()) {
            return Optional.empty();
        }
        List<ExamContribution> contributions = contributionRepository.findBySchoolExamIdIn(all.stream().map(SchoolExam::getId).toList());
        Set<Long> ownRounds = contributions.stream().filter(c -> ownPastExamIds.contains(c.getPastExamId()))
                .map(ExamContribution::getSchoolExamId).collect(Collectors.toSet());
        List<SchoolExam> rounds = all.stream().filter(r -> !ownRounds.contains(r.getId()) && r.getStats() != null).toList();
        if (rounds.isEmpty()) {
            return Optional.empty();
        }
        Set<Long> roundIds = rounds.stream().map(SchoolExam::getId).collect(Collectors.toSet());
        List<ExamContribution> used = contributions.stream().filter(c -> roundIds.contains(c.getSchoolExamId())).toList();
        int contributors = (int) used.stream().map(ExamContribution::getAcademyId).distinct().count();
        Map<Long, Integer> byRound = used.stream().collect(Collectors.groupingBy(ExamContribution::getSchoolExamId,
                Collectors.collectingAndThen(Collectors.mapping(ExamContribution::getAcademyId, Collectors.toSet()), Set::size)));

        Trend trend = SchoolTrendCalculator.calculate(rounds);
        String basis = "학교 DB 기출 %d회분 · 학원 %d곳".formatted(rounds.size(), contributors);
        String description = basis + "\n" + SchoolTrendSummarizer.describeTrend(trend) + "\n회차별:\n"
                + SchoolTrendSummarizer.describeRounds(rounds, byRound)
                + (trend.highlights().isEmpty() ? "" : "\n변화:\n" + trend.highlights().stream().map(h -> "- " + h)
                .collect(Collectors.joining("\n")));
        return Optional.of(new Input(w.getSchoolId(), w.getGrade(), rounds, contributors, trend, basis, description));
    }

    /**
     * 내 기출 통계에 학교 DB 회차 통계를 더한다 (내 기출이 없으면 own은 null).
     * [조건] 문구는 학교 DB에 없으므로 내 기출 것만, 배점 비중은 모든 회차에 배점이 있을 때만 계산한다.
     */
    public static ProfileStats merge(ProfileStats own, List<SchoolExam> rounds) {
        int examCount = (own == null ? 0 : own.examCount()) + rounds.size();
        int total = own == null ? 0 : own.totalQuestions();
        int objective = own == null ? 0 : own.objectiveCount();
        int subjective = own == null ? 0 : own.subjectiveCount();
        Map<QuestionType, Integer> typeCounts = new EnumMap<>(QuestionType.class);
        if (own != null) {
            own.typeCounts().forEach((t, c) -> typeCounts.merge(t, c, Integer::sum));
        }
        boolean pointsKnown = own == null || own.subjectivePointsRatio() != null;
        double points = own == null || own.subjectivePointsRatio() == null ? 0 : own.subjectivePointsRatio() * own.totalQuestions();
        for (SchoolExam round : rounds) {
            ExamRoundStats s = round.getStats();
            total += s.totalQuestions();
            objective += s.objectiveCount();
            subjective += s.subjectiveCount();
            s.typeCounts().forEach((t, c) -> typeCounts.merge(t, c, Integer::sum));
            if (s.subjectivePointsRatio() == null) {
                pointsKnown = false;
            } else {
                points += s.subjectivePointsRatio() * s.totalQuestions();
            }
        }
        Map<QuestionType, Integer> ordered = new LinkedHashMap<>();
        typeCounts.forEach(ordered::put);
        return new ProfileStats(examCount, total, objective, subjective, total == 0 ? 0 : round(subjective / (double) total),
                pointsKnown && total > 0 ? round(points / total) : null, ordered,
                own == null ? List.of() : own.frequentConditions(), rounds.size());
    }

    private static double round(double value) {
        return Math.round(value * 1000) / 1000.0;
    }
}
