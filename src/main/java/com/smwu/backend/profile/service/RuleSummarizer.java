package com.smwu.backend.profile.service;

import com.smwu.backend.ai.LlmClient;
import com.smwu.backend.ai.LlmRequest;
import com.smwu.backend.ai.LlmResult;
import com.smwu.backend.ai.prompt.PromptLoader;
import com.smwu.backend.pastexam.domain.ExamType;
import com.smwu.backend.pastexam.domain.PastExam;
import com.smwu.backend.pastexam.domain.PastPassage;
import com.smwu.backend.pastexam.domain.PastQuestion;
import com.smwu.backend.pastexam.extraction.QuestionType;
import com.smwu.backend.profile.domain.ProfileRule;
import com.smwu.backend.profile.domain.ProfileRule.RuleCategory;
import com.smwu.backend.profile.domain.ProfileRule.RuleSource;
import com.smwu.backend.profile.domain.ProfileStats;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 기출 서답형 문항을 LLM에 보내 출제 규칙과 대표 문항을 받는다.
 * LLM에는 DB ID 대신 이번 요청 안에서만 쓰는 키(Q1, Q2 ...)를 보내고, 응답의 키를 코드에서 ID로 바꾼다.
 * 근거 문항이 없는(=입력에 없는 키만 단) 규칙은 버린다.
 */
@Component
@RequiredArgsConstructor
public class RuleSummarizer {

    public static final String TASK = "summarize-rules";
    static final int MAX_RULES = 10;
    static final int MAX_EXAMPLES = 3;
    static final int MIN_EXAMPLES = 2;
    private static final int PASSAGE_MAX_CHARS = 1500;
    /**
     * 규칙 문장에 섞여 나온 요청 내부 키 (Q7 등). Java의 \b는 한글도 단어 문자로 봐서 "Q7에서는"을 못 찾으므로
     * 앞뒤가 영문·숫자가 아닌지로 판단한다
     */
    private static final Pattern QUESTION_KEY = Pattern.compile("(?<![A-Za-z0-9])Q\\d+(?![0-9])");

    private final LlmClient llmClient;
    private final PromptLoader promptLoader;

    /** @param model 규칙 요약에 쓴 모델. 서답형이 없어 LLM을 부르지 않았으면 null */
    public record Result(List<ProfileRule> rules, List<Long> exampleQuestionIds, String model) {
    }

    /** LLM 응답 형식 (prompts/summarize-rules.schema.json) */
    record Draft(List<RuleDraft> rules, List<String> exampleKeys) {
    }

    record RuleDraft(String text, RuleCategory category, List<String> evidenceKeys) {
    }

    /**
     * @param target              분석 대상 설명 (예: "워크스페이스 3의 기출 2개")
     * @param exams               분석에 쓴 기출 시험지 (시험지 표시용)
     * @param subjectiveQuestions 서답형 문항 (시험지 순서 → 문항 순서)
     * @param passagesByExam      시험지별 지문
     */
    public Result summarize(String target, ProfileStats stats, List<PastExam> exams, List<PastQuestion> subjectiveQuestions,
                            Map<Long, List<PastPassage>> passagesByExam) {
        if (subjectiveQuestions.isEmpty()) {
            return new Result(List.of(), List.of(), null);
        }
        Map<String, PastQuestion> keyed = new LinkedHashMap<>();
        for (int i = 0; i < subjectiveQuestions.size(); i++) {
            keyed.put("Q" + (i + 1), subjectiveQuestions.get(i));
        }

        LlmRequest request = LlmRequest.of(
                TASK,
                promptLoader.text(TASK + "-system"),
                promptLoader.render(TASK + "-user", Map.of(
                        "target", target,
                        "stats", describeStats(stats),
                        "questions", describeQuestions(exams, keyed, passagesByExam))),
                promptLoader.schema(TASK));
        LlmResult<Draft> result = llmClient.generate(request, Draft.class);
        return toResult(result.value(), keyed, result.model());
    }

    /** LLM 응답을 검증해서 규칙과 대표 문항으로 바꾼다 */
    static Result toResult(Draft draft, Map<String, PastQuestion> keyed, String model) {
        List<ProfileRule> rules = new ArrayList<>();
        Set<String> seenTexts = new HashSet<>();
        for (RuleDraft rule : draft.rules() == null ? List.<RuleDraft>of() : draft.rules()) {
            String text = replaceKeys(rule.text() == null ? "" : rule.text().strip(), keyed);
            List<Long> evidence = toIds(rule.evidenceKeys(), keyed);
            if (text.isEmpty() || evidence.isEmpty() || !seenTexts.add(text)) {
                continue;
            }
            rules.add(new ProfileRule("r" + (rules.size() + 1), text,
                    rule.category() == null ? RuleCategory.OTHER : rule.category(),
                    RuleSource.PAST_EXAM, evidence, false, null));
            if (rules.size() == MAX_RULES) {
                break;
            }
        }

        List<Long> examples = new ArrayList<>(toIds(draft.exampleKeys(), keyed));
        if (examples.size() > MAX_EXAMPLES) {
            examples = new ArrayList<>(examples.subList(0, MAX_EXAMPLES));
        }
        fillExamples(examples, keyed.values());
        return new Result(rules, examples, model);
    }

    /**
     * 대표 문항이 2개보다 적으면 채운다: 아직 고르지 않은 유형 → [조건]이 많은 문항 → 시험지 순서.
     */
    static void fillExamples(List<Long> examples, Collection<PastQuestion> candidates) {
        int target = Math.min(MIN_EXAMPLES, candidates.size());
        Set<QuestionType> chosenTypes = candidates.stream()
                .filter(q -> examples.contains(q.getId()))
                .map(PastQuestion::getType)
                .collect(Collectors.toCollection(HashSet::new));
        List<PastQuestion> ordered = new ArrayList<>(candidates);
        while (examples.size() < target) {
            ordered.sort(Comparator.<PastQuestion>comparingInt(q -> chosenTypes.contains(q.getType()) ? 1 : 0)
                    .thenComparing(Comparator.<PastQuestion>comparingInt(q -> q.getConditions().size()).reversed()));
            PastQuestion next = ordered.stream().filter(q -> !examples.contains(q.getId())).findFirst().orElse(null);
            if (next == null) {
                return;
            }
            examples.add(next.getId());
            chosenTypes.add(next.getType());
        }
    }

    /** 요청 내부 키(Q7)는 강사가 알 수 없으므로 "서답형 7번"처럼 바꾼다. 모르는 키는 그대로 둔다 */
    static String replaceKeys(String text, Map<String, PastQuestion> keyed) {
        Matcher matcher = QUESTION_KEY.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            PastQuestion q = keyed.get(matcher.group());
            matcher.appendReplacement(sb, Matcher.quoteReplacement(q == null ? matcher.group() : "서답형 " + q.getNo() + "번"));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    private static List<Long> toIds(List<String> keys, Map<String, PastQuestion> keyed) {
        if (keys == null) {
            return List.of();
        }
        Set<Long> ids = new LinkedHashSet<>();
        for (String key : keys) {
            PastQuestion q = key == null ? null : keyed.get(key.strip());
            if (q != null) {
                ids.add(q.getId());
            }
        }
        return List.copyOf(ids);
    }

    static String describeStats(ProfileStats stats) {
        StringBuilder sb = new StringBuilder();
        sb.append("- 기출 시험지 ").append(stats.examCount()).append("개, 전체 ").append(stats.totalQuestions())
                .append("문항 (객관식 ").append(stats.objectiveCount()).append(", 서답형 ").append(stats.subjectiveCount()).append(")\n");
        sb.append("- 서답형 문항 비율: ").append(percent(stats.subjectiveRatio())).append('\n');
        sb.append("- 서답형 배점 비중: ")
                .append(stats.subjectivePointsRatio() == null ? "배점 정보 부족" : percent(stats.subjectivePointsRatio())).append('\n');
        sb.append("- 유형별 문항 수: ").append(stats.typeCounts().entrySet().stream()
                .map(e -> e.getKey().getLabel() + " " + e.getValue())
                .collect(Collectors.joining(", "))).append('\n');
        if (!stats.frequentConditions().isEmpty()) {
            sb.append("- 2문항 이상에 나온 [조건]: ").append(stats.frequentConditions().stream()
                    .map(c -> "\"" + c.text() + "\" " + c.count() + "회")
                    .collect(Collectors.joining(", "))).append('\n');
        }
        return sb.toString().strip();
    }

    static String describeQuestions(List<PastExam> exams, Map<String, PastQuestion> keyed,
                                    Map<Long, List<PastPassage>> passagesByExam) {
        Map<Long, String> examLabels = new LinkedHashMap<>();
        for (int i = 0; i < exams.size(); i++) {
            examLabels.put(exams.get(i).getId(), "E" + (i + 1));
        }

        StringBuilder sb = new StringBuilder();
        for (PastExam exam : exams) {
            List<Map.Entry<String, PastQuestion>> examQuestions = keyed.entrySet().stream()
                    .filter(e -> e.getValue().getPastExamId().equals(exam.getId()))
                    .toList();
            if (examQuestions.isEmpty()) {
                continue;
            }
            String examLabel = examLabels.get(exam.getId());
            sb.append("### [").append(examLabel).append("] ").append(examTitle(exam)).append("\n\n");

            Set<String> usedCodes = new LinkedHashSet<>();
            examQuestions.forEach(e -> usedCodes.addAll(e.getValue().getPassageCodes()));
            for (PastPassage p : passagesByExam.getOrDefault(exam.getId(), List.of())) {
                if (usedCodes.contains(p.getCode())) {
                    sb.append("지문 ").append(examLabel).append('-').append(p.getCode())
                            .append(p.getTitle() == null ? "" : " (" + p.getTitle() + ")").append(":\n")
                            .append(truncate(p.getText())).append("\n\n");
                }
            }

            for (Map.Entry<String, PastQuestion> e : examQuestions) {
                PastQuestion q = e.getValue();
                sb.append(e.getKey()).append(" | ").append(examLabel).append(" 서답형 ").append(q.getNo()).append("번 | ")
                        .append(q.getType().getLabel());
                if (q.getPoints() != null) {
                    sb.append(" | ").append(formatPoints(q.getPoints())).append("점");
                }
                if (!q.getPassageCodes().isEmpty()) {
                    sb.append(" | 지문 ").append(q.getPassageCodes().stream()
                            .map(code -> examLabel + "-" + code).collect(Collectors.joining(", ")));
                }
                sb.append("\n  발문: ").append(q.getStem());
                if (q.getBody() != null) {
                    sb.append("\n  본문: ").append(q.getBody().replace("\n", "\n        "));
                }
                if (!q.getConditions().isEmpty()) {
                    sb.append("\n  조건: ").append(String.join(" / ", q.getConditions()));
                }
                if (!q.getChoices().isEmpty()) {
                    sb.append("\n  보기: ").append(String.join(" / ", q.getChoices()));
                }
                if (q.getAnswer() != null) {
                    sb.append("\n  정답: ").append(q.getAnswer().replace("\n", " "));
                }
                sb.append("\n\n");
            }
        }
        return sb.toString().strip();
    }

    public static String examTitle(PastExam exam) {
        return exam.getExamYear() + "년 " + exam.getSemester() + "학기 "
                + (exam.getExamType() == ExamType.MIDTERM ? "중간고사" : "기말고사");
    }

    private static String truncate(String text) {
        return text.length() <= PASSAGE_MAX_CHARS ? text : text.substring(0, PASSAGE_MAX_CHARS) + " ...(이하 생략)";
    }

    private static String percent(double ratio) {
        return Math.round(ratio * 1000) / 10.0 + "%";
    }

    private static String formatPoints(double points) {
        return points == Math.floor(points) ? String.valueOf((long) points) : String.valueOf(points);
    }
}
