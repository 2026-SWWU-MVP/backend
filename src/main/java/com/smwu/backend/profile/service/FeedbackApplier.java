package com.smwu.backend.profile.service;

import com.smwu.backend.ai.LlmClient;
import com.smwu.backend.ai.LlmRequest;
import com.smwu.backend.ai.LlmResult;
import com.smwu.backend.ai.prompt.PromptLoader;
import com.smwu.backend.pastexam.extraction.QuestionType;
import com.smwu.backend.profile.domain.ProfileRule;
import com.smwu.backend.profile.domain.ProfileRule.RuleCategory;
import com.smwu.backend.profile.domain.ProfileRule.RuleSource;
import com.smwu.backend.profile.domain.SchoolProfile;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 강사가 알려준 현장 정보를 LLM으로 프로필 변경안(강사 규칙 추가, 충돌하는 기출 규칙 비활성화, 유형 구성 변경)으로 바꾼다.
 * LLM은 변경안만 제안하고, 적용 가능한지는 코드가 검증한다 (없는 규칙 id, 강사 규칙 비활성화, 잘못된 유형 구성은 무시).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FeedbackApplier {

    public static final String TASK = "apply-feedback";
    static final int MAX_NEW_RULES = 3;

    private final LlmClient llmClient;
    private final PromptLoader promptLoader;

    /**
     * @param newRules    추가할 강사 규칙 (문장, 분류)
     * @param overrideIds 이번 시험에서 비활성화할 기출 규칙 id
     * @param typeMix     새 지문당 유형 구성 (검증 실패 시 기존 구성)
     */
    public record Result(List<NewRule> newRules, Set<String> overrideIds, Map<QuestionType, Integer> typeMix, String model) {
    }

    public record NewRule(String text, RuleCategory category) {
    }

    /** LLM 응답 형식 (prompts/apply-feedback.schema.json) */
    record Draft(List<NewRule> newRules, List<String> overrideRuleIds, List<MixItem> typeMix) {
    }

    record MixItem(QuestionType type, Integer count) {
    }

    public Result apply(SchoolProfile profile, String note) {
        LlmRequest request = LlmRequest.of(
                TASK,
                promptLoader.text(TASK + "-system"),
                promptLoader.render(TASK + "-user", Map.of(
                        "stats", RuleSummarizer.describeStats(profile.getStats()),
                        "rules", ProfileRules.describeRules(profile.getRules(), null),
                        "typeMix", ProfileRules.describeTypeMix(profile.getTypeMixPerPassage()),
                        "note", note.replace("\"", "'"))),
                promptLoader.schema(TASK));
        LlmResult<Draft> result = llmClient.generate(request, Draft.class);
        return toResult(result.value(), profile, result.model());
    }

    static Result toResult(Draft draft, SchoolProfile profile, String model) {
        Set<String> existingTexts = new HashSet<>();
        profile.getRules().forEach(r -> existingTexts.add(r.text().strip()));
        List<NewRule> newRules = new ArrayList<>();
        for (NewRule rule : draft.newRules() == null ? List.<NewRule>of() : draft.newRules()) {
            String text = rule.text() == null ? "" : rule.text().strip();
            if (!text.isEmpty() && existingTexts.add(text) && newRules.size() < MAX_NEW_RULES) {
                newRules.add(new NewRule(text, rule.category() == null ? RuleCategory.OTHER : rule.category()));
            }
        }

        // 비활성화는 아직 적용 중인 기출 규칙만
        Set<String> overridable = new HashSet<>();
        profile.getRules().stream()
                .filter(r -> r.source().isAnalysis() && !r.overridden())
                .map(ProfileRule::id)
                .forEach(overridable::add);
        Set<String> overrideIds = new LinkedHashSet<>();
        for (String id : draft.overrideRuleIds() == null ? List.<String>of() : draft.overrideRuleIds()) {
            if (id != null && overridable.contains(id.strip())) {
                overrideIds.add(id.strip());
            }
        }

        Map<QuestionType, Integer> typeMix = profile.getTypeMixPerPassage();
        if (draft.typeMix() != null && !draft.typeMix().isEmpty()) {
            Map<QuestionType, Integer> proposed = new EnumMap<>(QuestionType.class);
            draft.typeMix().forEach(item -> {
                if (item.type() != null) {
                    proposed.merge(item.type(), item.count() == null ? 0 : item.count(), Integer::sum);
                }
            });
            try {
                typeMix = ProfileRules.validateTypeMix(proposed);
            } catch (IllegalArgumentException e) {
                log.warn("강사 의견 반영: LLM이 제안한 유형 구성이 규칙에 맞지 않아 기존 구성 유지 ({})", proposed);
            }
        }
        return new Result(newRules, overrideIds, typeMix, model);
    }
}
