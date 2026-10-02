package com.smwu.backend.problem.type;

import com.smwu.backend.pastexam.extraction.QuestionType;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 조건 영작 (기출 서답형 "우리말과 같은 뜻이 되도록 주어진 단어를 활용하여 영작하시오").
 * 정답은 윗글의 한 문장(원문 그대로)이라 코드로 검증할 수 있다. LLM은 우리말 해석과 제시어(원형)를 만든다.
 */
@Component
public class GuidedWritingHandler implements ProblemTypeHandler<GuidedWritingHandler.Draft> {

    static final int MIN_WORDS = 7;
    static final int MAX_WORDS = 22;
    static final int MIN_GIVEN = 3;
    static final int MAX_GIVEN = 6;
    private static final Pattern HANGUL = Pattern.compile("[가-힣]");

    /**
     * @param sentence    정답이 될 윗글 문장 (그대로 복사)
     * @param korean      sentence의 자연스러운 우리말 해석
     * @param givenWords  제시어 (원형, 3~6개). 학생은 필요하면 어형을 바꿔 쓴다
     * @param explanation 해설 (한국어)
     */
    public record Draft(String sentence, String korean, List<String> givenWords, String explanation) {
    }

    @Override
    public QuestionType type() {
        return QuestionType.GUIDED_WRITING;
    }

    @Override
    public String promptName() {
        return "generate-guided-writing";
    }

    @Override
    public Class<Draft> draftType() {
        return Draft.class;
    }

    @Override
    public String stem(ProblemOptions options) {
        return "윗글의 내용과 일치하도록, 우리말과 같은 뜻이 되게 주어진 단어를 활용하여 영작하시오.";
    }

    @Override
    public List<String> conditions(ProblemOptions options) {
        // 단어 수 조건은 정답 문장에 맞춰 assemble에서 붙인다
        return List.of("주어진 단어를 모두 사용할 것.", "필요한 경우 어형을 바꿀 것.");
    }

    @Override
    public String requirements(ProblemOptions options) {
        return "- 정답 문장: " + MIN_WORDS + "~" + MAX_WORDS + "단어\n- 제시어: " + MIN_GIVEN + "~" + MAX_GIVEN + "개, 원형";
    }

    @Override
    public AssembledProblem assemble(Draft draft, PassageSource passage, ProblemOptions options, long seed) {
        if (draft.sentence() == null || draft.sentence().isBlank()) {
            throw new IllegalArgumentException("정답 문장(sentence)이 비어 있다.");
        }
        if (draft.korean() == null || draft.korean().isBlank()) {
            throw new IllegalArgumentException("우리말 해석(korean)이 비어 있다.");
        }
        List<String> given = (draft.givenWords() == null ? List.<String>of() : draft.givenWords()).stream()
                .map(String::strip).filter(w -> !w.isEmpty()).toList();
        String sentence = draft.sentence().strip();
        int words = TextNormalizer.wordCount(sentence);

        List<String> conditions = new ArrayList<>(conditions(options));
        conditions.add(words + "단어로 쓸 것.");
        String body = draft.korean().strip() + "\n(" + String.join(", ", given) + ")";
        return new AssembledProblem(type(), stem(options), conditions, body, List.of(), ProblemAnswer.sentence(sentence),
                sentence, draft.explanation() == null || draft.explanation().isBlank() ? null : draft.explanation().strip(),
                sentence);
    }

    @Override
    public List<ValidationCheck> validate(AssembledProblem problem, PassageSource passage, ProblemOptions options) {
        String sentence = problem.answer().sentence();
        String[] lines = problem.body().split("\n");
        String korean = lines[0];
        List<String> given = lines.length < 2 ? List.of()
                : List.of(lines[lines.length - 1].replaceAll("^\\(|\\)$", "").split(",\\s*"));
        List<ValidationCheck> checks = new ArrayList<>();

        checks.add(ValidationCheck.of("SENTENCE_IN_PASSAGE", TextNormalizer.containsSentence(passage.text(), sentence),
                "정답 문장이 윗글에 그대로 있지 않다. 윗글의 한 문장을 글자 그대로 복사해야 한다."));
        int words = TextNormalizer.wordCount(sentence);
        checks.add(ValidationCheck.of("SENTENCE_LENGTH", words >= MIN_WORDS && words <= MAX_WORDS,
                "정답 문장은 " + MIN_WORDS + "~" + MAX_WORDS + "단어여야 하는데 " + words + "단어다."));
        checks.add(ValidationCheck.of("KOREAN", HANGUL.matcher(korean).find(), "우리말 해석이 한국어가 아니다."));
        checks.add(ValidationCheck.of("GIVEN_COUNT", given.size() >= MIN_GIVEN && given.size() <= MAX_GIVEN,
                "제시어는 " + MIN_GIVEN + "~" + MAX_GIVEN + "개여야 하는데 " + given.size() + "개다."));

        List<String> sentenceWords = TextNormalizer.words(sentence);
        List<String> missing = given.stream().filter(g -> sentenceWords.stream().noneMatch(w -> sameLemma(g, w))).toList();
        checks.add(ValidationCheck.of("GIVEN_IN_SENTENCE", missing.isEmpty(),
                "제시어가 정답 문장에 (원형이나 규칙 변화형으로) 없다: " + missing + ". 불규칙 변화 동사는 제시어로 쓰지 말 것."));
        Set<String> seen = new HashSet<>();
        List<String> invalid = given.stream()
                .filter(g -> !TextNormalizer.isSingleWord(g) || !seen.add(g.toLowerCase(Locale.ROOT)))
                .toList();
        checks.add(ValidationCheck.of("GIVEN_SINGLE_WORDS", invalid.isEmpty(), "제시어는 서로 다른 한 단어여야 한다: " + invalid));
        checks.add(ValidationCheck.of("NOT_TOO_EASY", given.size() * 2 <= words,
                "제시어가 정답 문장 단어의 절반을 넘으면 너무 쉽다."));
        return checks;
    }

    /** 제시어(원형)와 문장 속 단어가 같은 단어인지: 같거나, 규칙 변화(-s, -es, -ed, -ing, -er, -est, -ly, y→i, e 탈락) */
    static boolean sameLemma(String given, String word) {
        String g = given.strip().toLowerCase(Locale.ROOT);
        String w = word.toLowerCase(Locale.ROOT);
        if (g.equals(w)) {
            return true;
        }
        String stem = g;
        if (g.endsWith("e") || g.endsWith("y")) {
            stem = g.substring(0, g.length() - 1);
        }
        if (stem.length() < 2 || !w.startsWith(stem)) {
            return false;
        }
        String suffix = w.substring(stem.length());
        return suffix.matches("(e|i|y)?(s|es|d|ed|ing|er|est|ly|ies|ied)?") || suffix.matches(".?(ed|ing)");
    }
}
