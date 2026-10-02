package com.smwu.backend.problem.type;

import com.smwu.backend.pastexam.extraction.QuestionType;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 요약문 빈칸 (출력예시 [서답형 1], [서답형 3]).
 * LLM은 요약문에 정답 단어를 [[단어]]로 표시해 돌려주고, 코드가 "(1) __________" 또는 "(1) c________"로 바꾼다.
 */
@Component
public class SummaryBlankHandler implements ProblemTypeHandler<SummaryBlankHandler.Draft> {

    private static final Pattern BLANK_MARK = Pattern.compile("\\[\\[([^\\[\\]]+)]]");
    private static final String BLANK = "__________";
    private static final String HINT_BLANK = "________";
    private static final int MIN_SUMMARY_WORDS = 10;
    private static final int MAX_SUMMARY_WORDS = 70;
    private static final int MIN_ANSWER_LETTERS = 3;
    /** 빈칸으로 만들면 안 되는 기능어 */
    private static final Set<String> FUNCTION_WORDS = Set.of("the", "and", "but", "for", "with", "from", "that",
            "this", "these", "those", "which", "who", "whom", "whose", "what", "when", "where", "while", "they", "them",
            "their", "there", "into", "onto", "than", "then", "also", "very", "been", "being", "have", "has", "had", "was",
            "were", "are", "its", "our", "your", "his", "her", "not", "can", "could", "will", "would", "should", "may", "might");

    /**
     * @param summary     빈칸 단어를 [[단어]]로 표시한 영어 요약문
     * @param evidence    요약의 근거가 되는 원문 문장 (그대로 복사)
     * @param explanation 해설 (한국어)
     */
    public record Draft(String summary, String evidence, String explanation) {
    }

    @Override
    public QuestionType type() {
        return QuestionType.SUMMARY_BLANK;
    }

    @Override
    public String promptName() {
        return "generate-summary-blank";
    }

    @Override
    public Class<Draft> draftType() {
        return Draft.class;
    }

    @Override
    public String stem(ProblemOptions options) {
        int n = options.blankCount();
        String blanks = n == 1 ? "빈칸" : n == 2 ? "빈칸 (1), (2)" : "빈칸 (1)~(" + n + ")";
        return "윗글의 내용을 요약할 때, " + blanks + "에 들어갈 말을 쓰시오.";
    }

    @Override
    public List<String> conditions(ProblemOptions options) {
        if (options.firstLetterHint()) {
            return List.of("각 빈칸에 한 단어씩 쓸 것.", "제시된 첫 철자로 시작할 것.", "문맥에 맞는 형태로 쓸 것.");
        }
        return List.of("각 빈칸에 한 단어씩 쓸 것.", "윗글에 나온 단어를 형태 변경 없이 쓸 것.");
    }

    @Override
    public String requirements(ProblemOptions options) {
        StringBuilder sb = new StringBuilder()
                .append("- 빈칸 수: ").append(options.blankCount()).append("개 ([[단어]] 표시를 정확히 ")
                .append(options.blankCount()).append("개)\n");
        if (options.firstLetterHint()) {
            sb.append("- 첫 철자 제시형: 빈칸 단어는 문맥에 맞게 형태를 바꿔도 되고, 윗글에 없는 동의어·파생어도 된다.\n")
                    .append("  단, 첫 철자만 보고도 답이 하나로 정해질 만큼 문맥이 분명해야 한다.\n");
        } else {
            sb.append("- 형태 변경 없음: 빈칸 단어는 윗글에 나온 단어를 형태 그대로 쓴다 (복수형·시제도 윗글과 같게).\n");
        }
        return sb.toString().strip();
    }

    @Override
    public AssembledProblem assemble(Draft draft, PassageSource passage, ProblemOptions options, long seed) {
        if (draft.summary() == null || draft.summary().isBlank()) {
            throw new IllegalArgumentException("요약문(summary)이 비어 있다.");
        }
        Matcher matcher = BLANK_MARK.matcher(draft.summary().strip());
        List<String> answers = new ArrayList<>();
        StringBuilder body = new StringBuilder();
        while (matcher.find()) {
            String answer = matcher.group(1).strip();
            answers.add(answer);
            int no = answers.size();
            String blank = options.firstLetterHint() && !answer.isEmpty() ? answer.charAt(0) + HINT_BLANK : BLANK;
            matcher.appendReplacement(body, Matcher.quoteReplacement("(" + no + ") " + blank));
        }
        matcher.appendTail(body);
        if (answers.size() != options.blankCount()) {
            throw new IllegalArgumentException("요약문의 [[단어]] 표시가 " + options.blankCount() + "개여야 하는데 "
                    + answers.size() + "개다.");
        }

        StringBuilder answerText = new StringBuilder();
        for (int i = 0; i < answers.size(); i++) {
            answerText.append(i == 0 ? "" : "   ").append('(').append(i + 1).append(") ").append(answers.get(i));
        }
        return new AssembledProblem(type(), stem(options), conditions(options), body.toString(), List.of(),
                ProblemAnswer.blanks(answers), answerText.toString(), blankToNull(stripMarks(draft.explanation())),
                blankToNull(draft.evidence()));
    }

    @Override
    public List<ValidationCheck> validate(AssembledProblem problem, PassageSource passage, ProblemOptions options) {
        List<String> answers = problem.answer().blanks();
        List<ValidationCheck> checks = new ArrayList<>();
        checks.add(ValidationCheck.of("BLANK_COUNT", answers.size() == options.blankCount(),
                "빈칸이 " + options.blankCount() + "개여야 하는데 " + answers.size() + "개다."));

        List<String> notSingle = answers.stream().filter(a -> !TextNormalizer.isSingleWord(a)).toList();
        checks.add(ValidationCheck.of("SINGLE_WORD", notSingle.isEmpty(), "빈칸 정답은 한 단어여야 한다: " + notSingle));

        List<String> weak = answers.stream()
                .filter(a -> a.strip().length() < MIN_ANSWER_LETTERS || FUNCTION_WORDS.contains(a.strip().toLowerCase(Locale.ROOT)))
                .toList();
        checks.add(ValidationCheck.of("CONTENT_WORD", weak.isEmpty(),
                "빈칸 정답은 글의 핵심 내용어(3글자 이상의 명사·동사·형용사·부사)여야 한다: " + weak));

        Set<String> seen = new HashSet<>();
        List<String> duplicated = answers.stream().filter(a -> !seen.add(a.toLowerCase(Locale.ROOT))).toList();
        checks.add(ValidationCheck.of("DISTINCT_ANSWERS", duplicated.isEmpty(), "빈칸 정답이 겹친다: " + duplicated));

        if (!options.firstLetterHint()) {
            List<String> missing = answers.stream().filter(a -> !TextNormalizer.containsWord(passage.text(), a)).toList();
            checks.add(ValidationCheck.of("WORD_IN_PASSAGE", missing.isEmpty(),
                    "\"형태 변경 없이\" 조건인데 윗글에 그 형태로 나오지 않는 단어: " + missing));
        }

        String filled = fill(problem.body(), answers);
        int words = TextNormalizer.wordCount(filled);
        checks.add(ValidationCheck.of("SUMMARY_LENGTH", words >= MIN_SUMMARY_WORDS && words <= MAX_SUMMARY_WORDS,
                "요약문은 " + MIN_SUMMARY_WORDS + "~" + MAX_SUMMARY_WORDS + "단어여야 하는데 " + words + "단어다."));

        List<String> copied = TextNormalizer.splitSentences(filled).stream()
                .filter(s -> TextNormalizer.wordCount(s) >= 6 && TextNormalizer.containsSentence(passage.text(), s))
                .toList();
        checks.add(ValidationCheck.of("NOT_COPIED", copied.isEmpty(),
                "요약문이 윗글 문장을 그대로 옮겼다 (바꿔 표현해야 한다): " + copied));
        return checks;
    }

    /** 빈칸 표기를 정답으로 채운 요약문 (검증용) */
    static String fill(String body, List<String> answers) {
        String filled = body;
        for (int i = 0; i < answers.size(); i++) {
            filled = filled.replaceFirst("\\(" + (i + 1) + "\\) \\S*_+", Matcher.quoteReplacement(answers.get(i)));
        }
        return filled;
    }

    /** 해설에 섞여 나온 [[단어]] 표시 제거 */
    static String stripMarks(String text) {
        return text == null ? null : BLANK_MARK.matcher(text).replaceAll("$1");
    }

    private static String blankToNull(String text) {
        return text == null || text.isBlank() ? null : text.strip();
    }
}
