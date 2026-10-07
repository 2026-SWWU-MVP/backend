package com.smwu.backend.problem.service;

import com.smwu.backend.auth.web.CurrentUserContext;
import com.smwu.backend.problem.domain.Problem;
import com.smwu.backend.problem.domain.ProblemReviewLog;
import com.smwu.backend.problem.domain.ProblemReviewLog.Action;
import com.smwu.backend.problem.domain.ProblemReviewLog.FieldChange;
import com.smwu.backend.problem.domain.ReviewStatus;
import com.smwu.backend.problem.dto.ProblemResponse;
import com.smwu.backend.problem.dto.UpdateProblemRequest;
import com.smwu.backend.problem.repository.ProblemRepository;
import com.smwu.backend.problem.repository.ProblemReviewLogRepository;
import com.smwu.backend.workspace.service.WorkspaceAccessChecker;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 강사 검수: 문항 수정·채택·폐기와 워크스페이스 문항 목록 (#16).
 * 채택·폐기·수정은 {@link ProblemReviewLog}로 남겨 다음 생성에 반영한다 (#42).
 */
@Service
@RequiredArgsConstructor
public class ProblemReviewService {

    private final ProblemGenerationService generationService;
    private final ProblemRepository problemRepository;
    private final ProblemReviewLogRepository reviewLogRepository;
    private final WorkspaceAccessChecker accessChecker;

    @Transactional
    public ProblemResponse update(Long problemId, UpdateProblemRequest request) {
        Problem problem = generationService.getProblem(problemId);
        Long userId = CurrentUserContext.userIdOrNull();

        Map<String, String> before = snapshot(problem);
        boolean edited = problem.edit(trim(request.stem()), trimAll(request.conditions()), request.body(), trimAll(request.choices()),
                trim(request.answerText()), trim(request.explanation()), userId);
        if (edited) {
            reviewLogRepository.save(new ProblemReviewLog(problem, Action.EDITED, null, changes(before, snapshot(problem)), userId));
        }

        ReviewStatus status = request.reviewStatus();
        if (status != null && status != problem.getReviewStatus()) {
            problem.review(status, userId);
            if (status != ReviewStatus.DRAFT) {
                reviewLogRepository.save(new ProblemReviewLog(problem,
                        status == ReviewStatus.ACCEPTED ? Action.ACCEPTED : Action.REJECTED,
                        status == ReviewStatus.REJECTED ? trimToNull(request.rejectReason()) : null, List.of(), userId));
            }
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

    /** 수정 전후 비교용: 강사가 고칠 수 있는 항목 */
    private static Map<String, String> snapshot(Problem p) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("stem", p.getStem());
        fields.put("conditions", String.join(" / ", p.getConditions()));
        fields.put("body", p.getBody());
        fields.put("choices", String.join(" / ", p.getChoices()));
        fields.put("answerText", p.getAnswerText());
        fields.put("explanation", p.getExplanation());
        return fields;
    }

    private static List<FieldChange> changes(Map<String, String> before, Map<String, String> after) {
        List<FieldChange> changes = new ArrayList<>();
        before.forEach((field, value) -> {
            if (!Objects.equals(value, after.get(field))) {
                changes.add(new FieldChange(field, value, after.get(field)));
            }
        });
        return changes;
    }

    private static String trim(String value) {
        return value == null ? null : value.strip();
    }

    private static String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static List<String> trimAll(List<String> values) {
        return values == null ? null : values.stream().map(String::strip).filter(v -> !v.isEmpty()).toList();
    }
}
