package com.smwu.backend.generation.service;

import com.smwu.backend.common.exception.BusinessException;
import com.smwu.backend.common.exception.ErrorCode;
import com.smwu.backend.generation.domain.GenerationJob;
import com.smwu.backend.generation.domain.GenerationPlan;
import com.smwu.backend.generation.domain.GenerationPlan.Slot;
import com.smwu.backend.generation.domain.GenerationPlan.TypeCount;
import com.smwu.backend.generation.dto.CreateGenerationJobRequest;
import com.smwu.backend.generation.dto.GenerationJobResponse;
import com.smwu.backend.generation.repository.GenerationJobRepository;
import com.smwu.backend.material.domain.Passage;
import com.smwu.backend.material.service.PassageService;
import com.smwu.backend.pastexam.extraction.QuestionType;
import com.smwu.backend.problem.domain.ValidationStatus;
import com.smwu.backend.problem.dto.ProblemResponse;
import com.smwu.backend.problem.repository.ProblemRepository;
import com.smwu.backend.problem.service.GenerationContextFactory;
import com.smwu.backend.problem.service.GenerationContextFactory.ProfileContext;
import com.smwu.backend.problem.service.ProblemGenerator;
import com.smwu.backend.problem.type.PassageSource;
import com.smwu.backend.workspace.service.WorkspaceAccessChecker;
import com.smwu.backend.auth.web.CurrentUserContext;
import com.smwu.backend.user.service.UserNames;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 문제 생성 작업: 확정 프로필 + 지문 N개 × 지문당 유형별 문항 수 → 문항마다 병렬 생성.
 * 작업을 저장(커밋)한 뒤 문항 작업을 실행기에 넣으므로, 202 응답 시점에 작업 조회가 바로 가능하다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GenerationJobService {

    static final int MAX_PROBLEMS = 60;

    private final GenerationJobRepository jobRepository;
    private final ProblemRepository problemRepository;
    private final GenerationContextFactory contextFactory;
    private final ProblemGenerator generator;
    private final PassageService passageService;
    private final WorkspaceAccessChecker accessChecker;
    private final GenerationSlotRunner slotRunner;
    private final UserNames userNames;

    public GenerationJobResponse create(Long workspaceId, CreateGenerationJobRequest request) {
        accessChecker.check(workspaceId);
        ProfileContext profileContext = contextFactory.fromConfirmedProfile(request.profileId());
        if (!profileContext.profile().getWorkspaceId().equals(workspaceId)) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "이 워크스페이스의 프로필이 아닙니다.");
        }

        Map<Long, PassageSource> passages = new HashMap<>();
        Set<Long> seen = new HashSet<>();
        for (Long passageId : request.passageIds()) {
            if (!seen.add(passageId)) {
                throw new BusinessException(ErrorCode.INVALID_REQUEST, "같은 지문이 두 번 들어 있습니다: " + passageId);
            }
            Passage passage = passageService.getPassage(passageId);
            if (!passage.getWorkspaceId().equals(workspaceId)) {
                throw new BusinessException(ErrorCode.INVALID_REQUEST, "이 워크스페이스의 지문이 아닙니다: " + passageId);
            }
            passages.put(passageId, passage.toSource());
        }

        GenerationPlan plan = new GenerationPlan(List.copyOf(request.passageIds()), perPassage(request, profileContext));
        if (plan.total() > MAX_PROBLEMS) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST,
                    "한 번에 " + MAX_PROBLEMS + "문항까지 만들 수 있습니다. (요청 " + plan.total() + "문항)");
        }

        GenerationJob job = jobRepository.save(GenerationJob.start(workspaceId, profileContext.profile().getId(), plan, CurrentUserContext.userIdOrNull()));
        // 같은 지문·같은 유형은 한 묶음으로 차례로 만들어 문항끼리 겹치지 않게 한다
        Map<String, List<Slot>> groups = new LinkedHashMap<>();
        plan.slots().forEach(slot -> groups.computeIfAbsent(slot.passageId() + ":" + slot.type(), k -> new ArrayList<>()).add(slot));
        groups.values().forEach(group ->
                slotRunner.run(job.getId(), group, profileContext, passages.get(group.get(0).passageId())));
        log.info("문제 생성 작업 {} 시작: 지문 {}개, {}문항", job.getId(), plan.passageIds().size(), plan.total());
        return GenerationJobResponse.of(job, 0, 0, 0, userNames.name(job.getCreatedBy()));
    }

    public GenerationJobResponse get(Long jobId) {
        return toResponse(getJob(jobId));
    }

    public List<GenerationJobResponse> list(Long workspaceId) {
        accessChecker.check(workspaceId);
        return jobRepository.findByWorkspaceIdOrderByIdDesc(workspaceId).stream().map(this::toResponse).toList();
    }

    /** 생성된 문항 (지문 순서 → 유형 순서) */
    public List<ProblemResponse> problems(Long jobId) {
        getJob(jobId);
        return problemRepository.findByGenerationJobIdOrderByJobSlotAscIdAsc(jobId).stream().map(ProblemResponse::of).toList();
    }

    private GenerationJob getJob(Long jobId) {
        GenerationJob job = jobRepository.findById(jobId).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        accessChecker.check(job.getWorkspaceId());
        return job;
    }

    private GenerationJobResponse toResponse(GenerationJob job) {
        Map<ValidationStatus, Long> counts = new EnumMap<>(ValidationStatus.class);
        problemRepository.countByValidationStatus(job.getId())
                .forEach(row -> counts.put((ValidationStatus) row[0], (Long) row[1]));
        return GenerationJobResponse.of(job, counts.getOrDefault(ValidationStatus.PASSED, 0L),
                counts.getOrDefault(ValidationStatus.NEEDS_REVIEW, 0L), counts.getOrDefault(ValidationStatus.FAILED, 0L),
                userNames.name(job.getCreatedBy()));
    }

    /** 요청에 유형 구성이 없으면 프로필의 지문당 유형 구성을 쓴다 */
    private List<TypeCount> perPassage(CreateGenerationJobRequest request, ProfileContext profileContext) {
        List<TypeCount> result = new ArrayList<>();
        if (request.perPassage() == null || request.perPassage().isEmpty()) {
            profileContext.profile().getTypeMixPerPassage().forEach((type, count) -> result.add(new TypeCount(type, count, null)));
        } else {
            Set<QuestionType> types = new HashSet<>();
            for (CreateGenerationJobRequest.TypeCountRequest t : request.perPassage()) {
                if (!types.add(t.type())) {
                    throw new BusinessException(ErrorCode.INVALID_REQUEST, "같은 유형이 두 번 들어 있습니다: " + t.type().getLabel());
                }
                result.add(new TypeCount(t.type(), t.count(), t.options()));
            }
        }
        for (TypeCount t : result) {
            if (!generator.supports(t.type())) {
                throw new BusinessException(ErrorCode.INVALID_REQUEST, "아직 생성할 수 없는 유형입니다: " + t.type().getLabel());
            }
        }
        if (result.isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "만들 유형이 없습니다.");
        }
        return result;
    }
}
