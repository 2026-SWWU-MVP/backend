package com.smwu.backend.schooldb.service;

import com.smwu.backend.ai.LlmClient;
import com.smwu.backend.ai.LlmRequest;
import com.smwu.backend.ai.LlmResult;
import com.smwu.backend.ai.prompt.PromptLoader;
import com.smwu.backend.pastexam.extraction.QuestionType;
import com.smwu.backend.schooldb.domain.ExamRoundStats;
import com.smwu.backend.schooldb.domain.SchoolExam;
import com.smwu.backend.schooldb.service.SchoolTrendCalculator.Trend;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 학교 DB 경향(코드 집계 통계)을 강사가 읽을 요약 문장으로 바꾼다. 여러 학원에 공유되므로 입력에 원문을 넣지 않고,
 * 출력에도 영어 문장(연속 6단어 이상)이 없는지 코드로 검사한다. 어기면 이유를 알려주고 한 번 다시 만든다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SchoolTrendSummarizer {

    public static final String TASK = "summarize-school-trend";
    static final int MAX_ATTEMPTS = 2;
    static final int HEADLINE_MAX = 80;
    static final int POINT_MAX = 200;
    static final int MAX_POINTS = 5;
    static final int MAX_TIPS = 3;
    /** 영어 단어 6개 이상이 이어지면 원문 인용으로 본다 */
    static final Pattern ENGLISH_RUN = Pattern.compile("[A-Za-z]+(?:[\\s,'’-]+[A-Za-z]+){5,}");

    private final LlmClient llmClient;
    private final PromptLoader promptLoader;

    /** LLM 응답 형식 (prompts/summarize-school-trend.schema.json) */
    record Draft(String headline, List<String> points, List<String> prepTips) {
    }

    /** @param attempts LLM 호출 횟수 */
    public record Summary(String headline, List<String> points, List<String> prepTips, String model, int attempts) {
    }

    public Summary summarize(String target, String basis, Trend trend, List<SchoolExam> rounds, Map<Long, Integer> contributorsByRound) {
        String userPrompt = promptLoader.render(TASK + "-user", Map.of(
                "target", target,
                "basis", basis,
                "trend", describeTrend(trend),
                "rounds", describeRounds(rounds, contributorsByRound),
                "highlights", trend.highlights().isEmpty() ? "(없음)"
                        : trend.highlights().stream().map(h -> "- " + h).collect(Collectors.joining("\n"))));

        List<String> problems = List.of();
        Draft draft = null;
        String model = null;
        int attempt = 0;
        while (attempt < MAX_ATTEMPTS) {
            attempt++;
            String prompt = problems.isEmpty() ? userPrompt : userPrompt + "\n\n## 이전 응답에서 지켜지지 않은 점 (반드시 고칠 것)\n"
                    + problems.stream().map(p -> "- " + p).collect(Collectors.joining("\n"));
            LlmResult<Draft> result = llmClient.generate(
                    LlmRequest.of(TASK, promptLoader.text(TASK + "-system"), prompt, promptLoader.schema(TASK)), Draft.class);
            draft = result.value();
            model = result.model();
            problems = check(draft);
            if (problems.isEmpty()) {
                break;
            }
            log.info("학교 경향 요약 attempt={} 검사 실패: {}", attempt, problems);
        }
        return clean(draft, target, basis, model, attempt);
    }

    /** 원문 인용(영어 6단어 이상), 길이 위반 */
    static List<String> check(Draft draft) {
        List<String> problems = new ArrayList<>();
        if (draft.headline() == null || draft.headline().isBlank()) {
            problems.add("headline이 비어 있다.");
        } else {
            checkText("headline", draft.headline(), HEADLINE_MAX, problems);
        }
        List<String> points = draft.points() == null ? List.of() : draft.points();
        if (points.isEmpty()) {
            problems.add("points가 비어 있다.");
        }
        for (int i = 0; i < points.size(); i++) {
            checkText("points[" + i + "]", points.get(i), POINT_MAX, problems);
        }
        List<String> tips = draft.prepTips() == null ? List.of() : draft.prepTips();
        for (int i = 0; i < tips.size(); i++) {
            checkText("prepTips[" + i + "]", tips.get(i), POINT_MAX, problems);
        }
        return problems;
    }

    private static void checkText(String field, String text, int max, List<String> problems) {
        if (text == null || text.isBlank()) {
            problems.add(field + "가 비어 있다.");
            return;
        }
        if (ENGLISH_RUN.matcher(text).find()) {
            problems.add(field + "에 영어 문장이 들어 있다. 영어 표현 없이 한국어로만 쓸 것: " + text);
        }
        if (text.strip().length() > max) {
            problems.add(field + "가 " + max + "자를 넘는다. 더 짧게 쓸 것.");
        }
    }

    /** 다시 만들어도 남은 위반 문장은 버린다. headline이 쓸 수 없으면 코드로 만든다 */
    private static Summary clean(Draft draft, String target, String basis, String model, int attempts) {
        String headline = draft.headline() != null && ok(draft.headline(), HEADLINE_MAX) ? draft.headline().strip()
                : target + " · " + basis;
        List<String> points = keep(draft.points(), MAX_POINTS);
        List<String> tips = keep(draft.prepTips(), MAX_TIPS);
        return new Summary(headline, points, tips, model, attempts);
    }

    private static List<String> keep(List<String> items, int max) {
        return items == null ? List.of() : items.stream()
                .filter(t -> t != null && ok(t, POINT_MAX))
                .map(String::strip)
                .distinct()
                .limit(max)
                .toList();
    }

    private static boolean ok(String text, int max) {
        return !text.isBlank() && text.strip().length() <= max && !ENGLISH_RUN.matcher(text).find();
    }

    static String describeTrend(Trend trend) {
        List<String> lines = new ArrayList<>();
        lines.add("- 기출 " + trend.examCount() + "회분, 회차당 평균 " + Math.round(trend.averageQuestions()) + "문항");
        lines.add("- 서술형 문항 비중 " + percent(trend.weightedSubjectiveRatio())
                + (trend.weightedPointsRatio() == null ? "" : ", 서술형 배점 비중 " + percent(trend.weightedPointsRatio())));
        lines.add("- 유형별 비중: " + trend.typeRatios().entrySet().stream().limit(10)
                .map(e -> e.getKey().getLabel() + " " + percent(e.getValue())).collect(Collectors.joining(", ")));
        lines.add("- 지문 1개당 서술형 구성: " + mix(trend.typeMixPerPassage()));
        trend.byYear().forEach(y -> lines.add("- " + y.year() + "년 (" + y.examCount() + "회): 서술형 비중 " + percent(y.subjectiveRatio())));
        return String.join("\n", lines);
    }

    static String describeRounds(List<SchoolExam> rounds, Map<Long, Integer> contributorsByRound) {
        return rounds.stream().filter(r -> r.getStats() != null)
                .sorted(Comparator.comparingInt(SchoolExam::order))
                .map(r -> {
                    ExamRoundStats s = r.getStats();
                    return "- " + SchoolTrendCalculator.label(r) + " (학원 " + contributorsByRound.getOrDefault(r.getId(), 1) + "곳): "
                            + s.totalQuestions() + "문항, 객관식 " + s.objectiveCount() + " / 서술형 " + s.subjectiveCount()
                            + " (" + percent(s.subjectiveRatio()) + "), 지문 " + s.passageCount() + "개, 유형: "
                            + s.typeCounts().entrySet().stream()
                            .sorted(Map.Entry.<QuestionType, Integer>comparingByValue().reversed())
                            .map(e -> e.getKey().getLabel() + " " + e.getValue()).collect(Collectors.joining(", "));
                })
                .collect(Collectors.joining("\n"));
    }

    private static String mix(Map<QuestionType, Integer> mix) {
        return mix.entrySet().stream().map(e -> e.getKey().getLabel() + " " + e.getValue()).collect(Collectors.joining(", "));
    }

    private static String percent(double ratio) {
        return Math.round(ratio * 100) + "%";
    }
}
