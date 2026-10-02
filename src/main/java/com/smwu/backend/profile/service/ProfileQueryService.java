package com.smwu.backend.profile.service;

import com.smwu.backend.common.exception.BusinessException;
import com.smwu.backend.common.exception.ErrorCode;
import com.smwu.backend.pastexam.domain.PastExam;
import com.smwu.backend.pastexam.domain.PastQuestion;
import com.smwu.backend.pastexam.extraction.ExtractedExam.QuestionSection;
import com.smwu.backend.pastexam.repository.PastExamRepository;
import com.smwu.backend.pastexam.repository.PastQuestionRepository;
import com.smwu.backend.profile.domain.ProfileRule;
import com.smwu.backend.profile.domain.SchoolProfile;
import com.smwu.backend.profile.dto.ProfileResponse;
import com.smwu.backend.profile.dto.ProfileResponse.ExamRef;
import com.smwu.backend.profile.dto.ProfileResponse.ExampleView;
import com.smwu.backend.profile.dto.ProfileResponse.QuestionRef;
import com.smwu.backend.profile.dto.ProfileResponse.RuleView;
import com.smwu.backend.profile.dto.ProfileSummaryResponse;
import com.smwu.backend.profile.repository.SchoolProfileRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 출제 프로필 조회. 근거·대표 문항을 화면 표시용 이름과 함께 풀어서 준다 */
@Service
@RequiredArgsConstructor
public class ProfileQueryService {

    static final String DELETED_QUESTION_LABEL = "삭제된 기출 문항";

    private final SchoolProfileRepository profileRepository;
    private final PastQuestionRepository pastQuestionRepository;
    private final PastExamRepository pastExamRepository;

    @Transactional(readOnly = true)
    public List<ProfileSummaryResponse> list(Long workspaceId) {
        checkWorkspaceAccess(workspaceId);
        return profileRepository.findByWorkspaceIdOrderByVersionDesc(workspaceId).stream()
                .map(ProfileSummaryResponse::of)
                .toList();
    }

    @Transactional(readOnly = true)
    public ProfileResponse get(Long profileId) {
        SchoolProfile profile = getProfile(profileId);

        Set<Long> questionIds = new HashSet<>(profile.getExampleQuestionIds());
        profile.getRules().forEach(r -> questionIds.addAll(r.evidenceQuestionIds()));
        Map<Long, PastQuestion> questions = pastQuestionRepository.findAllById(questionIds).stream()
                .collect(Collectors.toMap(PastQuestion::getId, Function.identity()));
        Set<Long> examIds = new HashSet<>(profile.getSourceExamIds());
        questions.values().forEach(q -> examIds.add(q.getPastExamId()));
        Map<Long, PastExam> exams = pastExamRepository.findAllById(examIds).stream()
                .collect(Collectors.toMap(PastExam::getId, Function.identity()));

        List<RuleView> rules = profile.getRules().stream()
                .map(r -> toRuleView(r, questions, exams))
                .toList();
        List<ExampleView> examples = profile.getExampleQuestionIds().stream()
                .map(questions::get)
                .filter(Objects::nonNull)
                .map(q -> new ExampleView(q.getId(), label(q, exams), q.getType(), q.getStem(), q.getBody(),
                        List.copyOf(q.getConditions()), List.copyOf(q.getChoices()), q.getAnswer(), q.getPoints()))
                .toList();
        List<ExamRef> sourceExams = profile.getSourceExamIds().stream()
                .map(id -> new ExamRef(id, exams.containsKey(id) ? RuleSummarizer.examTitle(exams.get(id)) : "삭제된 기출"))
                .toList();

        return new ProfileResponse(profile.getId(), profile.getWorkspaceId(), profile.getVersion(), profile.getParentId(),
                profile.getStatus(), profile.getOrigin(), profile.getStats(), rules, profile.getTypeMixPerPassage(),
                List.copyOf(profile.getTeacherNotes()), List.copyOf(profile.getChangeSummary()), examples, sourceExams,
                profile.getLlmModel(), profile.getCreatedAt(), profile.getConfirmedAt());
    }

    public SchoolProfile getProfile(Long profileId) {
        SchoolProfile profile = profileRepository.findById(profileId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        checkWorkspaceAccess(profile.getWorkspaceId());
        return profile;
    }

    /**
     * TODO(#10): WorkspaceAccessChecker로 현재 사용자의 학원 워크스페이스인지 확인 (아니면 404).
     * 기출 API(PastExamService.checkWorkspaceAccess)와 같은 시점에 연결한다.
     */
    public void checkWorkspaceAccess(Long workspaceId) {
    }

    private static RuleView toRuleView(ProfileRule rule, Map<Long, PastQuestion> questions, Map<Long, PastExam> exams) {
        List<QuestionRef> evidence = rule.evidenceQuestionIds().stream()
                .map(id -> new QuestionRef(id, questions.containsKey(id) ? label(questions.get(id), exams) : DELETED_QUESTION_LABEL))
                .toList();
        return new RuleView(rule.id(), rule.text(), rule.category(), rule.source(), evidence, rule.overridden(), rule.noteIndex());
    }

    private static String label(PastQuestion q, Map<Long, PastExam> exams) {
        PastExam exam = exams.get(q.getPastExamId());
        String examTitle = exam == null ? "삭제된 기출" : RuleSummarizer.examTitle(exam);
        return examTitle + " " + (q.getSection() == QuestionSection.SUBJECTIVE ? "서답형 " : "") + q.getNo() + "번";
    }
}
