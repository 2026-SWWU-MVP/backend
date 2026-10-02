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
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 확정 프로필로 지문 1개에 대해 문제 1개를 만들어 저장한다. 생성 작업(#17)은 문항마다 이 서비스를 부른다.
 * LLM 호출 동안 트랜잭션을 열지 않고, 저장은 repository.save의 짧은 트랜잭션으로 한다.
 */
@Service
@RequiredArgsConstructor
public class ProblemGenerationService {

    private final GenerationContextFactory contextFactory;
    private final ProblemGenerator generator;
    private final ProblemRepository problemRepository;

    public Problem generate(Long profileId, PassageSource passage, QuestionType type, ProblemOptions options, long seed) {
        return generate(contextFactory.fromConfirmedProfile(profileId), passage, type, options, null, seed);
    }

    /**
     * @param profileContext 확정 프로필과 생성 맥락 (작업 하나에서 여러 문항을 만들 때 한 번만 만든다)
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
                passage.passageId(), generationJobId, type, resolved, outcome.problem(), outcome.status(),
                new ValidationReport(outcome.checks(), outcome.attempts(), outcome.attemptFailures()), outcome.model());
        return problemRepository.save(problem);
    }
}
