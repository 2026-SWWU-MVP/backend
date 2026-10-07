package com.smwu.backend.problem.service;

import com.smwu.backend.auth.web.CurrentUserContext;
import com.smwu.backend.problem.domain.Problem;
import com.smwu.backend.problem.domain.ReviewStatus;
import com.smwu.backend.problem.dto.ProblemResponse;
import com.smwu.backend.problem.dto.UpdateProblemRequest;
import com.smwu.backend.problem.repository.ProblemRepository;
import com.smwu.backend.workspace.service.WorkspaceAccessChecker;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** 강사 검수: 문항 수정·채택·폐기와 워크스페이스 문항 목록 (#16) */
@Service
@RequiredArgsConstructor
public class ProblemReviewService {

    private final ProblemGenerationService generationService;
    private final ProblemRepository problemRepository;
    private final WorkspaceAccessChecker accessChecker;

    @Transactional
    public ProblemResponse update(Long problemId, UpdateProblemRequest request) {
        Problem problem = generationService.getProblem(problemId);
        Long userId = CurrentUserContext.userIdOrNull();
        problem.edit(trim(request.stem()), trimAll(request.conditions()), request.body(), trimAll(request.choices()),
                trim(request.answerText()), trim(request.explanation()), userId);
        if (request.reviewStatus() != null) {
            problem.review(request.reviewStatus(), userId);
        }
        return ProblemResponse.of(problem);
    }

    /** @param reviewStatus null이면 전체 */
    @Transactional(readOnly = true)
    public List<ProblemResponse> list(Long workspaceId, ReviewStatus reviewStatus) {
        accessChecker.check(workspaceId);
        List<Problem> problems = reviewStatus == null
                ? problemRepository.findByWorkspaceIdOrderByIdDesc(workspaceId)
                : problemRepository.findByWorkspaceIdAndReviewStatusOrderByIdDesc(workspaceId, reviewStatus);
        return problems.stream().map(ProblemResponse::of).toList();
    }

    private static String trim(String value) {
        return value == null ? null : value.strip();
    }

    private static List<String> trimAll(List<String> values) {
        return values == null ? null : values.stream().map(String::strip).filter(v -> !v.isEmpty()).toList();
    }
}
