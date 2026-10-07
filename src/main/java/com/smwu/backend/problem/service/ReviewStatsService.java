package com.smwu.backend.problem.service;

import com.smwu.backend.problem.domain.Problem;
import com.smwu.backend.problem.domain.ProblemReviewLog.Action;
import com.smwu.backend.problem.domain.ReviewStatus;
import com.smwu.backend.problem.dto.ReviewStatsResponse;
import com.smwu.backend.problem.dto.ReviewStatsResponse.Counts;
import com.smwu.backend.problem.dto.ReviewStatsResponse.JobCounts;
import com.smwu.backend.problem.dto.ReviewStatsResponse.TypeCounts;
import com.smwu.backend.problem.repository.ProblemRepository;
import com.smwu.backend.problem.repository.ProblemReviewLogRepository;
import com.smwu.backend.workspace.service.WorkspaceAccessChecker;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

/** 워크스페이스의 채택·폐기·수정 통계 (#42) */
@Service
@RequiredArgsConstructor
public class ReviewStatsService {

    private final ProblemRepository problemRepository;
    private final ProblemReviewLogRepository reviewLogRepository;
    private final WorkspaceAccessChecker accessChecker;

    @Transactional(readOnly = true)
    public ReviewStatsResponse stats(Long workspaceId) {
        accessChecker.check(workspaceId);
        List<Problem> problems = problemRepository.findByWorkspaceIdOrderByIdDesc(workspaceId);
        Set<Long> edited = new HashSet<>(reviewLogRepository.findProblemIds(workspaceId, Action.EDITED));

        List<TypeCounts> byType = problems.stream()
                .collect(Collectors.groupingBy(Problem::getType, TreeMap::new, Collectors.toList()))
                .entrySet().stream()
                .map(e -> new TypeCounts(e.getKey(), e.getKey().getLabel(), counts(e.getValue(), edited)))
                .toList();

        Map<Long, List<Problem>> jobs = problems.stream().filter(p -> p.getGenerationJobId() != null)
                .collect(Collectors.groupingBy(Problem::getGenerationJobId, TreeMap::new, Collectors.toList()));
        List<JobCounts> byJob = jobs.entrySet().stream()
                .map(e -> new JobCounts(e.getKey(),
                        e.getValue().stream().map(Problem::getCreatedAt).min(Comparator.naturalOrder()).orElse((LocalDateTime) null),
                        counts(e.getValue(), edited)))
                .toList();
        return new ReviewStatsResponse(counts(problems, edited), byType, byJob);
    }

    static Counts counts(List<Problem> problems, Set<Long> edited) {
        int accepted = (int) problems.stream().filter(p -> p.getReviewStatus() == ReviewStatus.ACCEPTED).count();
        int rejected = (int) problems.stream().filter(p -> p.getReviewStatus() == ReviewStatus.REJECTED).count();
        int editedCount = (int) problems.stream().filter(p -> edited.contains(p.getId())).count();
        Double rate = accepted + rejected == 0 ? null : Math.round(accepted * 1000.0 / (accepted + rejected)) / 1000.0;
        return new Counts(problems.size(), accepted, rejected, problems.size() - accepted - rejected, editedCount, rate);
    }
}
