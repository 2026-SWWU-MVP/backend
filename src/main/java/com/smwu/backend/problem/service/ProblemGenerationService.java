package com.smwu.backend.problem.service;

import com.smwu.backend.common.exception.BusinessException;
import com.smwu.backend.common.exception.ErrorCode;
import com.smwu.backend.pastexam.extraction.QuestionType;
import com.smwu.backend.problem.domain.Problem;
import com.smwu.backend.problem.domain.ValidationReport;
import com.smwu.backend.problem.repository.ProblemRepository;
import com.smwu.backend.problem.service.GenerationContextFactory.ProfileContext;
import com.smwu.backend.problem.type.PassageSource;
import com.smwu.backend.problem.type.ProblemOptions;
import com.smwu.backend.profile.domain.SchoolProfile;
import com.smwu.backend.profile.service.ProfileQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 확정 프로필로 지문 1개에 대해 문제 1개를 만들어 저장한다. 생성 작업(#17)은 문항마다 이 서비스를 부른다.
 * LLM 호출 동안 트랜잭션을 열지 않고, 저장만 짧은 트랜잭션으로 한다.
 */
@Service
@RequiredArgsConstructor
public class ProblemGenerationService {

    private final GenerationContextFactory contextFactory;
    private final ProblemGenerator generator;
    private final ProblemRepository problemRepository;
    private final ProfileQueryService profileQueryService;
    private final PlatformTransactionManager transactionManager;

    public Problem generate(Long profileId, PassageSource passage, QuestionType type, ProblemOptions options, long seed) {
        return generate(contextFactory.fromConfirmedProfile(profileId), passage, type, options, null, seed);
    }

    /**
     * @param profileContext  확정 프로필과 생성 맥락 (작업 하나에서 여러 문항을 만들 때 한 번만 만든다)
     * @param generationJobId 생성 작업 ID (#17), 단건이면 null
     * @param seed            [보기] 섞기 시드 (문항 위치 등으로 정하면 다시 생성해도 같은 순서)
     */
    public Problem generate(ProfileContext profileContext, PassageSource passage, QuestionType type, ProblemOptions options,
                            Long generationJobId, long seed) {
        if (!generator.supports(type)) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "아직 생성할 수 없는 유형입니다: " + type.getLabel());
        }
        ProblemOptions resolved = (options == null ? ProblemOptions.defaults() : options).withDefaults();
        ProblemGenerator.Outcome outcome = generator.generate(profileContext.context(), passage, type, resolved, seed);

        Problem problem = Problem.generated(profileContext.profile().getWorkspaceId(), profileContext.profile().getId(),
                passage, generationJobId, type, resolved, outcome.problem(), outcome.status(), report(outcome), outcome.model());
        return problemRepository.save(problem);
    }

    /**
     * 개별 재생성: 같은 지문(사본)·유형·옵션·프로필 버전으로 다시 만들어 같은 문항을 덮어쓴다.
     * 프로필이 그 사이 새 버전으로 대체되었어도 처음 만든 버전을 쓴다 (같은 조건으로 다시 뽑기).
     */
    public Problem regenerate(Long problemId) {
        Problem problem = getProblem(problemId);
        if (problem.getPassageText() == null || problem.getPassageText().isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "원문 지문이 없어 다시 생성할 수 없습니다.");
        }
        SchoolProfile profile = profileQueryService.getProfile(problem.getProfileId());
        GenerationContext context = contextFactory.build(profile);
        long seed = problem.getId() * 31 + (problem.getValidationReport() == null ? 0 : problem.getValidationReport().attempts());
        ProblemGenerator.Outcome outcome = generator.generate(context, problem.passageSource(), problem.getType(),
                problem.getOptions() == null ? ProblemOptions.defaults() : problem.getOptions(), seed);

        return new TransactionTemplate(transactionManager).execute(status -> {
            Problem current = problemRepository.findById(problemId).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
            current.regenerated(outcome.problem(), outcome.status(), report(outcome), outcome.model());
            return current;
        });
    }

    public Problem getProblem(Long problemId) {
        Problem problem = problemRepository.findById(problemId).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        profileQueryService.checkWorkspaceAccess(problem.getWorkspaceId());
        return problem;
    }

    private static ValidationReport report(ProblemGenerator.Outcome outcome) {
        return new ValidationReport(outcome.checks(), outcome.attempts(), outcome.attemptFailures());
    }
}
