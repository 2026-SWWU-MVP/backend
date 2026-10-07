package com.smwu.backend.problem.service;

import com.smwu.backend.common.exception.BusinessException;
import com.smwu.backend.common.exception.ErrorCode;
import com.smwu.backend.pastexam.domain.PastQuestion;
import com.smwu.backend.pastexam.repository.PastQuestionRepository;
import com.smwu.backend.profile.domain.ProfileRule;
import com.smwu.backend.profile.domain.ProfileRule.RuleSource;
import com.smwu.backend.profile.domain.ProfileStatus;
import com.smwu.backend.profile.domain.SchoolProfile;
import com.smwu.backend.profile.domain.TeacherNote;
import com.smwu.backend.profile.service.ProfileQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 확정 프로필로 문제 생성 프롬프트용 맥락(강사 정보, 기출 규칙, 대표 문항)을 만든다 */
@Component
@RequiredArgsConstructor
public class GenerationContextFactory {

    private final ProfileQueryService profileQueryService;
    private final PastQuestionRepository pastQuestionRepository;
    private final TeacherPreferences teacherPreferences;

    public record ProfileContext(SchoolProfile profile, GenerationContext context) {
    }

    /** @throws BusinessException 확정되지 않은 프로필이면 409 PROFILE_NOT_CONFIRMED */
    public ProfileContext fromConfirmedProfile(Long profileId) {
        SchoolProfile profile = profileQueryService.getProfile(profileId);
        if (profile.getStatus() != ProfileStatus.CONFIRMED) {
            throw new BusinessException(ErrorCode.PROFILE_NOT_CONFIRMED);
        }
        return new ProfileContext(profile, build(profile));
    }

    public GenerationContext build(SchoolProfile profile) {
        List<String> teacherRules = profile.getRules().stream()
                .filter(r -> r.source() == RuleSource.TEACHER)
                .map(ProfileRule::text)
                .toList();
        List<String> pastExamRules = profile.getRules().stream()
                .filter(r -> r.source().isAnalysis() && !r.overridden())
                .map(ProfileRule::text)
                .toList();
        Map<Long, PastQuestion> questions = pastQuestionRepository.findAllById(profile.getExampleQuestionIds()).stream()
                .collect(Collectors.toMap(PastQuestion::getId, Function.identity()));
        List<GenerationContext.Example> examples = profile.getExampleQuestionIds().stream()
                .map(questions::get)
                .filter(Objects::nonNull)
                .map(q -> new GenerationContext.Example(q.getType().getLabel(), describe(q)))
                .toList();
        return new GenerationContext(profile.getId(), profile.getTeacherNotes().stream().map(TeacherNote::text).toList(),
                teacherRules, pastExamRules, examples, teacherPreferences.forWorkspace(profile.getWorkspaceId()));
    }

    public static String describe(PastQuestion q) {
        StringBuilder sb = new StringBuilder("발문: ").append(q.getStem());
        if (q.getBody() != null) {
            sb.append("\n본문: ").append(q.getBody());
        }
        if (!q.getConditions().isEmpty()) {
            sb.append("\n[조건] ").append(String.join(" / ", q.getConditions()));
        }
        if (!q.getChoices().isEmpty()) {
            sb.append("\n[보기] ").append(String.join(" / ", q.getChoices()));
        }
        if (q.getAnswer() != null) {
            sb.append("\n정답: ").append(q.getAnswer().replace("\n", " "));
        }
        return sb.toString();
    }
}
