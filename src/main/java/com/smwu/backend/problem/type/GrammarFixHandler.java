package com.smwu.backend.problem.type;

import com.smwu.backend.pastexam.extraction.QuestionType;
import com.smwu.backend.problem.type.ProblemAnswer.Correction;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 어법 오류 수정 (기출 서답형 "윗글의 밑줄 친 ①~⑤ 중 어법상 틀린 것을 찾아 바르게 고쳐 쓰시오").
 * LLM은 밑줄 5곳을 (바로 앞 단어들, 원래 표현, 보이는 표현)으로만 돌려주고, 코드가 원문에서 그 위치를 찾아 ①[보이는 표현]을 넣는다.
 * 긴 지문 전체를 LLM이 옮겨 쓰면 문장이 빠지거나 바뀌는 일이 잦아서(#21 실험), 원문은 코드가 그대로 쓴다.
 */
@Component
public class GrammarFixHandler implements ProblemTypeHandler<GrammarFixHandler.Draft> {

    static final int TARGETS = 5;
    static final int MAX_TARGET_WORDS = 4;
    private static final String[] CIRCLED = {"①", "②", "③", "④", "⑤"};
    private static final Pattern MARKED = Pattern.compile("[①-⑤]\\[([^\\[\\]]+)]");

    /**
     * @param targets     밑줄 5곳 (윗글에 나오는 순서대로)
     * @param explanation 해설 (한국어, 틀린 이유와 문법 포인트)
     */
    public record Draft(List<Target> targets, String explanation) {
    }

    /**
     * @param before   원래 표현 바로 앞의 단어 2~5개 (윗글 그대로, 위치를 찾는 데 쓴다)
     * @param original 윗글에 있는 원래 표현 (1~4단어)
     * @param shown    문제에 보일 표현. 틀린 곳이면 어법상 틀린 형태, 맞는 곳이면 original과 같게
     */
    public record Target(String before, String original, String shown) {
    }

    @Override
    public QuestionType type() {
        return QuestionType.GRAMMAR_FIX;
    }

    @Override
    public String promptName() {
        return "generate-grammar-fix";
    }

    @Override
    public Class<Draft> draftType() {
        return Draft.class;
    }

    @Override
    public String stem(ProblemOptions options) {
        return "윗글의 밑줄 친 ①~⑤ 중 어법상 틀린 것을 " + options.errorCount() + "개 찾아 바르게 고쳐 쓰시오.";
    }

    @Override
    public List<String> conditions(ProblemOptions options) {
        return List.of("틀린 것의 번호와 고친 표현을 모두 쓸 것.", "밑줄 친 부분만 고쳐 쓸 것.");
    }

    @Override
    public String requirements(ProblemOptions options) {
        return "- 밑줄: 정확히 " + TARGETS + "곳, 각 1~" + MAX_TARGET_WORDS + "단어, 윗글 앞부분부터 순서대로\n"
                + "- 어법상 틀린 곳: 정확히 " + options.errorCount() + "곳 (나머지는 shown = original)";
    }

    @Override
    public AssembledProblem assemble(Draft draft, PassageSource passage, ProblemOptions options, long seed) {
        List<Target> targets = draft.targets() == null ? List.of() : draft.targets();
        if (targets.size() != TARGETS) {
            throw new IllegalArgumentException("밑줄(targets)이 " + TARGETS + "개여야 하는데 " + targets.size() + "개다.");
        }
        String text = passage.text();
        StringBuilder body = new StringBuilder();
        List<Correction> corrections = new ArrayList<>();
        String firstErrorPhrase = null;
        int cursor = 0;
        for (int i = 0; i < TARGETS; i++) {
            Target target = targets.get(i);
            if (blank(target.original()) || blank(target.shown())) {
                throw new IllegalArgumentException((i + 1) + "번 밑줄의 original 또는 shown이 비어 있다.");
            }
            Matcher matcher = locate(target).matcher(text);
            if (!matcher.find(cursor)) {
                // before를 조금 다르게 쓴 경우: 원래 표현이 앞 밑줄 뒤에 한 번만 나오면 그 위치로 본다
                matcher = locate(new Target(null, target.original(), target.shown())).matcher(text);
                if (!matcher.find(cursor) || matcher.find(matcher.end()) || !matcher.find(cursor)) {
                    throw new IllegalArgumentException((i + 1) + "번 밑줄 \"" + target.before() + " " + target.original()
                            + "\"을 윗글에서 (앞 밑줄 뒤에서) 찾을 수 없다. before와 original은 윗글 그대로, 윗글 순서대로 써야 한다.");
                }
            }
            int start = matcher.start(1);
            int end = matcher.end(1);
            body.append(text, cursor, start).append(CIRCLED[i]).append('[').append(target.shown().strip()).append(']');
            cursor = end;
            if (!target.shown().strip().equals(target.original().strip())) {
                corrections.add(new Correction(i + 1, target.shown().strip(), target.original().strip()));
                if (firstErrorPhrase == null) {
                    firstErrorPhrase = text.substring(matcher.start(), end);
                }
            }
        }
        body.append(text.substring(cursor));

        String answerText = corrections.stream()
                .map(c -> CIRCLED[c.number() - 1] + " " + c.shown() + " → " + c.corrected())
                .collect(Collectors.joining("   "));
        return new AssembledProblem(type(), stem(options), conditions(options), body.toString(), List.of(),
                ProblemAnswer.corrections(corrections), answerText, blank(draft.explanation()) ? null : draft.explanation().strip(),
                firstErrorPhrase == null ? text.substring(0, Math.min(80, text.length())) : firstErrorPhrase);
    }

    @Override
    public List<ValidationCheck> validate(AssembledProblem problem, PassageSource passage, ProblemOptions options) {
        List<Correction> corrections = problem.answer().corrections();
        List<ValidationCheck> checks = new ArrayList<>();
        checks.add(ValidationCheck.of("ERROR_COUNT", corrections.size() == options.errorCount(),
                "어법상 틀린 곳(shown ≠ original)은 " + options.errorCount() + "개여야 하는데 " + corrections.size() + "개다."));

        List<String> longTargets = new ArrayList<>();
        Matcher matcher = MARKED.matcher(problem.body());
        while (matcher.find()) {
            if (TextNormalizer.wordCount(matcher.group(1)) > MAX_TARGET_WORDS) {
                longTargets.add(matcher.group(1));
            }
        }
        checks.add(ValidationCheck.of("TARGET_LENGTH", longTargets.isEmpty(),
                "밑줄은 " + MAX_TARGET_WORDS + "단어 이하여야 한다: " + longTargets));
        List<String> caseOnly = corrections.stream()
                .filter(c -> c.shown().equalsIgnoreCase(c.corrected()))
                .map(Correction::shown)
                .toList();
        checks.add(ValidationCheck.of("REAL_ERROR", caseOnly.isEmpty(), "대소문자만 다른 것은 어법 오류가 아니다: " + caseOnly));
        return checks;
    }

    /** before 단어들 + 원래 표현을 공백·문장부호 차이에 관계없이 찾는다. 그룹 1이 원래 표현 위치 */
    static Pattern locate(Target target) {
        String before = blank(target.before()) ? "" : Arrays.stream(target.before().strip().split("\\s+"))
                .map(GrammarFixHandler::quoteWord)
                .collect(Collectors.joining("\\W+")) + "\\W+";
        String original = Arrays.stream(target.original().strip().split("\\s+"))
                .map(GrammarFixHandler::quoteWord)
                .collect(Collectors.joining("\\s+"));
        return Pattern.compile(before + "(?<![A-Za-z])(" + original + ")(?![A-Za-z])");
    }

    private static String quoteWord(String word) {
        return Pattern.quote(word).replace("’", "\\E['’]\\Q").replace("'", "\\E['’]\\Q");
    }

    private static boolean blank(String text) {
        return text == null || text.isBlank();
    }
}
