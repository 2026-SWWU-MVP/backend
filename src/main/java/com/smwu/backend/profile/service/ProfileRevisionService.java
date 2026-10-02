package com.smwu.backend.profile.service;

import com.smwu.backend.common.exception.BusinessException;
import com.smwu.backend.common.exception.ErrorCode;
import com.smwu.backend.pastexam.extraction.QuestionType;
import com.smwu.backend.profile.domain.ProfileOrigin;
import com.smwu.backend.profile.domain.ProfileRule;
import com.smwu.backend.profile.domain.ProfileRule.RuleCategory;
import com.smwu.backend.profile.domain.ProfileRule.RuleSource;
import com.smwu.backend.profile.domain.ProfileStatus;
import com.smwu.backend.profile.domain.SchoolProfile;
import com.smwu.backend.profile.domain.TeacherNote;
import com.smwu.backend.profile.dto.FeedbackRequest;
import com.smwu.backend.profile.dto.ManualEditRequest;
import com.smwu.backend.profile.dto.ManualEditRequest.RuleEdit;
import com.smwu.backend.profile.dto.ProfileResponse;
import com.smwu.backend.profile.repository.SchoolProfileRepository;
import com.smwu.backend.profile.service.ProfileDiff.Snapshot;
import com.smwu.backend.profile.service.ProfileSourceLoader.Source;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 출제 프로필 강사 검토 루프: ① 확정 ② AI 재검토 ③ 강사 의견 반영 ④ 직접 수정.
 * ②~④는 기존 버전을 고치지 않고 새 DRAFT 버전을 만들며, 변경점(changeSummary)은 {@link ProfileDiff}로 계산한다.
 * 통계(stats)는 기출에서 나온 사실이므로 어떤 경로로도 바뀌지 않는다.
 */
@Slf4j
@Service
public class ProfileRevisionService {

    private final SchoolProfileRepository profileRepository;
    private final ProfileQueryService queryService;
    private final ProfileSourceLoader sourceLoader;
    private final ProfileRechecker rechecker;
    private final FeedbackApplier feedbackApplier;
    private final TransactionTemplate transactionTemplate;

    public ProfileRevisionService(SchoolProfileRepository profileRepository, ProfileQueryService queryService,
                                  ProfileSourceLoader sourceLoader, ProfileRechecker rechecker,
                                  FeedbackApplier feedbackApplier, PlatformTransactionManager transactionManager) {
        this.profileRepository = profileRepository;
        this.queryService = queryService;
        this.sourceLoader = sourceLoader;
        this.rechecker = rechecker;
        this.feedbackApplier = feedbackApplier;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /** ① 확정. 워크스페이스의 기존 확정본은 SUPERSEDED가 된다. 이미 확정본이면 그대로 */
    public ProfileResponse confirm(Long profileId) {
        transactionTemplate.executeWithoutResult(status -> {
            SchoolProfile profile = queryService.getProfile(profileId);
            if (profile.getStatus() == ProfileStatus.CONFIRMED) {
                return;
            }
            profileRepository.findByWorkspaceIdAndStatus(profile.getWorkspaceId(), ProfileStatus.CONFIRMED)
                    .forEach(SchoolProfile::supersede);
            profile.confirm(null); // TODO(#6): 로그인 사용자 ID
        });
        return queryService.get(profileId);
    }

    /** 현재 확정 프로필 (문제 생성에 쓰는 버전) */
    public ProfileResponse getConfirmed(Long workspaceId) {
        queryService.checkWorkspaceAccess(workspaceId);
        List<SchoolProfile> confirmed = profileRepository.findByWorkspaceIdAndStatus(workspaceId, ProfileStatus.CONFIRMED);
        if (confirmed.isEmpty()) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "확정된 출제 프로필이 없습니다. 프로필을 확인하고 확정해 주세요.");
        }
        return queryService.get(confirmed.get(0).getId());
    }

    /** ② AI 재검토: [기출] 규칙을 기출 문항과 다시 대조 → 새 DRAFT */
    public ProfileResponse recheck(Long profileId) {
        SchoolProfile base = queryService.getProfile(profileId);
        Source source = sourceLoader.loadExams(base.getSourceExamIds());
        if (source.exams().isEmpty()) {
            throw new BusinessException(ErrorCode.NO_EXTRACTED_PAST_EXAM,
                    "이 프로필을 만든 기출이 삭제되었거나 다시 추출 중입니다. 기출로 프로필을 새로 만들어 주세요.");
        }
        ProfileRechecker.Result result = rechecker.recheck(base, source);
        return saveRevision(base, ProfileOrigin.AI_RECHECK, result.rules(), base.getTypeMixPerPassage(),
                base.getTeacherNotes(), result.exampleQuestionIds(), result.model());
    }

    /** ③ 강사 의견 반영: 의견 원문 저장 + [강사] 규칙 추가 + 충돌하는 [기출] 규칙 비활성화 + 유형 구성 조정 → 새 DRAFT */
    public ProfileResponse applyFeedback(Long profileId, FeedbackRequest request) {
        SchoolProfile base = queryService.getProfile(profileId);
        String note = request.text().strip();
        FeedbackApplier.Result result = feedbackApplier.apply(base, note);

        List<TeacherNote> notes = new ArrayList<>(base.getTeacherNotes());
        notes.add(new TeacherNote(note, Boolean.TRUE.equals(request.persistent()), null)); // TODO(#6): 작성자
        int noteIndex = notes.size() - 1;

        List<ProfileRule> rules = new ArrayList<>();
        for (ProfileRule rule : base.getRules()) {
            rules.add(result.overrideIds().contains(rule.id())
                    ? new ProfileRule(rule.id(), rule.text(), rule.category(), rule.source(), rule.evidenceQuestionIds(), true, rule.noteIndex())
                    : rule);
        }
        for (FeedbackApplier.NewRule newRule : result.newRules()) {
            rules.add(new ProfileRule(ProfileRules.nextRuleId(rules), newRule.text(), newRule.category(),
                    RuleSource.TEACHER, List.of(), false, noteIndex));
        }
        return saveRevision(base, ProfileOrigin.TEACHER_FEEDBACK, rules, result.typeMix(), notes,
                base.getExampleQuestionIds(), result.model());
    }

    /** ④ 직접 수정: 규칙 추가·삭제·수정, 유형 구성 수정 → 새 DRAFT (LLM 호출 없음) */
    public ProfileResponse manualEdit(Long profileId, ManualEditRequest request) {
        SchoolProfile base = queryService.getProfile(profileId);
        List<ProfileRule> rules = request.rules() == null ? base.getRules() : editRules(base.getRules(), request.rules());
        Map<QuestionType, Integer> typeMix = base.getTypeMixPerPassage();
        if (request.typeMixPerPassage() != null) {
            try {
                typeMix = ProfileRules.validateTypeMix(request.typeMixPerPassage());
            } catch (IllegalArgumentException e) {
                throw new BusinessException(ErrorCode.INVALID_REQUEST, e.getMessage());
            }
        }
        return saveRevision(base, ProfileOrigin.MANUAL_EDIT, rules, typeMix, base.getTeacherNotes(),
                base.getExampleQuestionIds(), null);
    }

    static List<ProfileRule> editRules(List<ProfileRule> current, List<RuleEdit> edits) {
        Map<String, ProfileRule> byId = new HashMap<>();
        current.forEach(r -> byId.put(r.id(), r));
        Set<String> seen = new HashSet<>();
        List<ProfileRule> result = new ArrayList<>();
        for (RuleEdit edit : edits) {
            String text = edit.text().strip();
            if (edit.id() == null || edit.id().isBlank()) {
                // 직접 추가한 규칙은 [강사] 규칙 (특정 의견에서 나온 것이 아니므로 noteIndex 없음)
                List<ProfileRule> forIds = new ArrayList<>(current);
                forIds.addAll(result);
                result.add(new ProfileRule(ProfileRules.nextRuleId(forIds), text,
                        edit.category() == null ? RuleCategory.OTHER : edit.category(), RuleSource.TEACHER, List.of(), false, null));
                continue;
            }
            ProfileRule existing = byId.get(edit.id());
            if (existing == null) {
                throw new BusinessException(ErrorCode.INVALID_REQUEST, "없는 규칙입니다: " + edit.id());
            }
            if (!seen.add(edit.id())) {
                throw new BusinessException(ErrorCode.INVALID_REQUEST, "같은 규칙이 두 번 들어 있습니다: " + edit.id());
            }
            // 비활성화는 [기출] 규칙에만 의미가 있다 (강사 규칙은 지우면 된다)
            boolean overridden = existing.source() == RuleSource.PAST_EXAM && edit.overridden() != null
                    ? edit.overridden() : existing.overridden();
            result.add(new ProfileRule(existing.id(), text, edit.category() == null ? existing.category() : edit.category(),
                    existing.source(), existing.evidenceQuestionIds(), overridden, existing.noteIndex()));
        }
        return result;
    }

    private ProfileResponse saveRevision(SchoolProfile base, ProfileOrigin origin, List<ProfileRule> rules,
                                         Map<QuestionType, Integer> typeMix, List<TeacherNote> notes,
                                         List<Long> examples, String llmModel) {
        List<String> changes = ProfileDiff.describe(
                new Snapshot(base.getRules(), base.getTypeMixPerPassage(), base.getTeacherNotes(), base.getExampleQuestionIds()),
                new Snapshot(rules, typeMix, notes, examples));
        SchoolProfile saved = transactionTemplate.execute(status -> {
            int nextVersion = profileRepository.findTopByWorkspaceIdOrderByVersionDesc(base.getWorkspaceId())
                    .map(p -> p.getVersion() + 1).orElse(1);
            List<String> summary = new ArrayList<>();
            summary.add("v" + base.getVersion() + "에서 " + originLabel(origin) + "로 만든 버전입니다.");
            summary.addAll(changes);
            return profileRepository.save(base.revise(nextVersion, origin, rules, typeMix, notes, examples, summary,
                    llmModel, null)); // TODO(#6): 로그인 사용자 ID
        });
        log.info("출제 프로필 {} workspaceId={} v{} → v{}", origin, base.getWorkspaceId(), base.getVersion(), saved.getVersion());
        return queryService.get(saved.getId());
    }

    private static String originLabel(ProfileOrigin origin) {
        return switch (origin) {
            case AI_RECHECK -> "AI 재검토";
            case TEACHER_FEEDBACK -> "강사 의견 반영";
            case MANUAL_EDIT -> "직접 수정";
            case INITIAL_ANALYSIS -> "기출 분석";
        };
    }
}
