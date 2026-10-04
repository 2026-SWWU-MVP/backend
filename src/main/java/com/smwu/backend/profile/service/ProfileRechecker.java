package com.smwu.backend.profile.service;

import com.smwu.backend.ai.LlmClient;
import com.smwu.backend.ai.LlmRequest;
import com.smwu.backend.ai.LlmResult;
import com.smwu.backend.ai.prompt.PromptLoader;
import com.smwu.backend.pastexam.domain.PastQuestion;
import com.smwu.backend.profile.domain.ProfileRule;
import com.smwu.backend.profile.domain.ProfileRule.RuleCategory;
import com.smwu.backend.profile.domain.ProfileRule.RuleSource;
import com.smwu.backend.profile.domain.SchoolProfile;
import com.smwu.backend.profile.service.ProfileSourceLoader.Source;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * AI 재검토: 기존 [기출] 규칙을 기출 문항과 다시 대조해 유지·수정·삭제·추가한다. [강사] 규칙은 건드리지 않는다.
 * 유지·수정된 규칙은 원래 id와 비활성화 여부를 그대로 가져간다 (강사가 비활성화한 규칙이 되살아나지 않게).
 */
@Component
@RequiredArgsConstructor
public class ProfileRechecker {

    public static final String TASK = "recheck-profile";

    private final LlmClient llmClient;
    private final PromptLoader promptLoader;

    /** @param rules 재검토된 [기출] 규칙 + 기존 [강사] 규칙 */
    public record Result(List<ProfileRule> rules, List<Long> exampleQuestionIds, String model) {
    }

    /** LLM 응답 형식 (prompts/recheck-profile.schema.json) */
    record Draft(List<RuleDraft> rules, List<String> exampleKeys) {
    }

    record RuleDraft(String previousId, String text, RuleCategory category, List<String> evidenceKeys) {
    }

    public Result recheck(SchoolProfile profile, Source source) {
        Map<String, PastQuestion> keyed = new LinkedHashMap<>();
        List<PastQuestion> subjective = source.subjectiveQuestions();
        for (int i = 0; i < subjective.size(); i++) {
            keyed.put("Q" + (i + 1), subjective.get(i));
        }
        Map<Long, String> evidenceKeys = new HashMap<>();
        keyed.forEach((key, q) -> evidenceKeys.put(q.getId(), key));

        LlmRequest request = LlmRequest.of(
                TASK,
                promptLoader.text(TASK + "-system"),
                promptLoader.render(TASK + "-user", Map.of(
                        "target", source.target(),
                        "stats", RuleSummarizer.describeStats(profile.getStats()),
                        "rules", ProfileRules.describeRules(profile.getRules(), evidenceKeys),
                        "questions", RuleSummarizer.describeQuestions(source.exams(), keyed, source.passagesByExam()))),
                promptLoader.schema(TASK));
        LlmResult<Draft> result = llmClient.generate(request, Draft.class);
        return toResult(result.value(), profile, keyed, result.model());
    }

    static Result toResult(Draft draft, SchoolProfile profile, Map<String, PastQuestion> keyed, String model) {
        Map<String, ProfileRule> previousPastExamRules = new HashMap<>();
        profile.getRules().stream()
                .filter(r -> r.source() == RuleSource.PAST_EXAM)
                .forEach(r -> previousPastExamRules.put(r.id(), r));

        List<ProfileRule> rules = new ArrayList<>();
        Set<String> usedIds = new HashSet<>();
        Set<String> seenTexts = new HashSet<>();
        List<ProfileRule> allForIds = new ArrayList<>(profile.getRules());
        for (RuleDraft rule : draft.rules() == null ? List.<RuleDraft>of() : draft.rules()) {
            String text = RuleSummarizer.replaceKeys(rule.text() == null ? "" : rule.text().strip(), keyed);
            List<Long> evidence = RuleSummarizer.toIds(rule.evidenceKeys(), keyed);
            if (text.isEmpty() || evidence.isEmpty() || !seenTexts.add(text) || rules.size() == RuleSummarizer.MAX_RULES) {
                continue;
            }
            ProfileRule previous = rule.previousId() == null ? null : previousPastExamRules.get(rule.previousId().strip());
            String id;
            boolean overridden = false;
            if (previous != null && usedIds.add(previous.id())) {
                id = previous.id();
                overridden = previous.overridden();
            } else {
                id = ProfileRules.nextRuleId(allForIds);
            }
            ProfileRule checked = new ProfileRule(id, text, rule.category() == null ? RuleCategory.OTHER : rule.category(),
                    RuleSource.PAST_EXAM, evidence, overridden, null);
            rules.add(checked);
            allForIds.add(checked);
        }
        profile.getRules().stream()
                .filter(r -> r.source() != RuleSource.PAST_EXAM)
                .forEach(rules::add);

        List<Long> examples = new ArrayList<>(RuleSummarizer.toIds(draft.exampleKeys(), keyed));
        if (examples.size() > RuleSummarizer.MAX_EXAMPLES) {
            examples = new ArrayList<>(examples.subList(0, RuleSummarizer.MAX_EXAMPLES));
        }
        RuleSummarizer.fillExamples(examples, keyed.values());
        return new Result(rules, examples, model);
    }
}
