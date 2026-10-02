package com.smwu.backend.problem.type;

import com.smwu.backend.pastexam.extraction.QuestionType;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * 어구 배열 (출력예시 [서답형 2]).
 * LLM은 원문 문장과 그 문장을 원래 순서대로 나눈 어구를 돌려주고, [보기] 순서 섞기는 코드가 한다 (시드 고정이라 재현 가능).
 */
@Component
public class SentenceOrderHandler implements ProblemTypeHandler<SentenceOrderHandler.Draft> {

    /** 출력예시 [보기]는 8조각. 4조각 이하는 조각이 길어져 너무 쉽다 */
    static final int MIN_CHUNKS = 5;
    static final int MAX_CHUNKS = 10;
    static final int MAX_CHUNK_WORDS = 6;
    private static final int SHUFFLE_TRIES = 50;
    /** [조건]이 "쉼표와 마침표"만 다루므로, 학생이 위치를 알 수 없는 문장부호가 있는 문장은 쓰지 않는다 */
    private static final Pattern OTHER_PUNCTUATION = Pattern.compile("[\"'‘’“”:;()\\[\\]–—]|\\s-\\s");
    /** 단어 안의 아포스트로피(veterinarian's, don't)와 복수 소유격(mothers')은 문장부호로 보지 않는다 */
    private static final Pattern APOSTROPHE = Pattern.compile("(?<=[A-Za-z])['’](?=[A-Za-z])|(?<=s)['’](?=\\s)");

    /**
     * @param sentence    윗글에서 그대로 복사한 대상 문장
     * @param chunks      대상 문장을 원래 순서대로 나눈 어구 (문장부호 제외)
     * @param explanation 해설 (한국어, 구문 포인트)
     */
    public record Draft(String sentence, List<String> chunks, String explanation) {
    }

    @Override
    public QuestionType type() {
        return QuestionType.SENTENCE_ORDER;
    }

    @Override
    public String promptName() {
        return "generate-sentence-order";
    }

    @Override
    public Class<Draft> draftType() {
        return Draft.class;
    }

    @Override
    public String stem(ProblemOptions options) {
        return "윗글에 나온 문장이 되도록 [보기]의 어구를 배열하시오.";
    }

    @Override
    public List<String> conditions(ProblemOptions options) {
        return List.of("[보기]의 모든 어구를 한 번씩 사용할 것.", "단어를 추가하거나 형태를 바꾸지 말 것.", "쉼표와 마침표를 알맞게 쓸 것.");
    }

    @Override
    public String requirements(ProblemOptions options) {
        return "- 대상 문장: " + options.minWords() + "단어 이상\n"
                + "- 어구 수: " + MIN_CHUNKS + "~" + MAX_CHUNKS + "개, 어구 하나는 1~" + MAX_CHUNK_WORDS + "단어";
    }

    @Override
    public AssembledProblem assemble(Draft draft, PassageSource passage, ProblemOptions options, long seed) {
        if (draft.sentence() == null || draft.sentence().isBlank()) {
            throw new IllegalArgumentException("대상 문장(sentence)이 비어 있다.");
        }
        List<String> chunks = (draft.chunks() == null ? List.<String>of() : draft.chunks()).stream()
                .map(SentenceOrderHandler::cleanChunk)
                .filter(c -> !c.isEmpty())
                .toList();
        if (chunks.size() < MIN_CHUNKS || chunks.size() > MAX_CHUNKS) {
            throw new IllegalArgumentException("어구는 " + MIN_CHUNKS + "~" + MAX_CHUNKS + "개여야 하는데 " + chunks.size() + "개다.");
        }

        List<Integer> shuffled = shuffle(chunks.size(), seed);
        List<String> choices = shuffled.stream().map(chunks::get).toList();
        // chunkOrder[k] = 정답 k번째 어구가 [보기]에서 몇 번째에 있는지
        List<Integer> chunkOrder = IntStream.range(0, chunks.size()).map(shuffled::indexOf).boxed().toList();

        String sentence = draft.sentence().strip();
        return new AssembledProblem(type(), stem(options), conditions(options), null, choices,
                ProblemAnswer.ordering(sentence, chunkOrder), sentence,
                draft.explanation() == null || draft.explanation().isBlank() ? null : draft.explanation().strip(),
                sentence);
    }

    @Override
    public List<ValidationCheck> validate(AssembledProblem problem, PassageSource passage, ProblemOptions options) {
        String sentence = problem.answer().sentence();
        List<String> choices = problem.choices();
        List<String> ordered = problem.answer().chunkOrder().stream().map(choices::get).toList();
        List<ValidationCheck> checks = new ArrayList<>();

        checks.add(ValidationCheck.of("SENTENCE_IN_PASSAGE", TextNormalizer.containsSentence(passage.text(), sentence),
                "대상 문장이 윗글에 그대로 있지 않다. 윗글의 한 문장을 글자 그대로 복사해야 한다."));
        checks.add(ValidationCheck.of("CHUNKS_MATCH_SENTENCE",
                TextNormalizer.normalizeLoose(String.join(" ", ordered)).equals(TextNormalizer.normalizeLoose(sentence)),
                "어구를 순서대로 이으면 대상 문장과 같아야 하는데 다르다: \"" + String.join(" / ", ordered) + "\""));
        String withoutApostrophes = APOSTROPHE.matcher(sentence).replaceAll("");
        checks.add(ValidationCheck.of("SIMPLE_PUNCTUATION", !OTHER_PUNCTUATION.matcher(withoutApostrophes).find(),
                "대상 문장에 따옴표·콜론·세미콜론·괄호·대시가 있으면 학생이 위치를 알 수 없다. 쉼표와 마침표만 있는 문장을 고른다."));
        int words = TextNormalizer.wordCount(sentence);
        checks.add(ValidationCheck.of("MIN_WORDS", words >= options.minWords(),
                "대상 문장은 " + options.minWords() + "단어 이상이어야 하는데 " + words + "단어다."));
        List<String> longChunks = choices.stream().filter(c -> TextNormalizer.wordCount(c) > MAX_CHUNK_WORDS).toList();
        checks.add(ValidationCheck.of("CHUNK_LENGTH", longChunks.isEmpty(),
                "어구 하나는 " + MAX_CHUNK_WORDS + "단어 이하여야 한다: " + longChunks));
        Set<String> seen = new HashSet<>();
        List<String> duplicated = choices.stream().filter(c -> !seen.add(c.toLowerCase(Locale.ROOT))).toList();
        checks.add(ValidationCheck.of("DISTINCT_CHUNKS", duplicated.isEmpty(),
                "같은 어구가 두 번 있으면 정답이 여러 개가 된다. 겹치지 않게 나눠야 한다: " + duplicated));
        boolean shuffledOk = !IntStream.range(0, choices.size()).allMatch(i -> problem.answer().chunkOrder().get(i) == i);
        checks.add(ValidationCheck.of("SHUFFLED", shuffledOk, "[보기]가 정답 순서 그대로다."));
        return checks;
    }

    /** 앞뒤 문장부호와 공백 제거 (조건 "쉼표와 마침표를 알맞게 쓸 것" — [보기]에는 문장부호를 넣지 않는다) */
    static String cleanChunk(String chunk) {
        return chunk == null ? "" : chunk.strip().replaceAll("^[\\s,.;:!?]+|[\\s,.;:!?]+$", "").strip();
    }

    /** 정답 순서와 다르고, 제자리에 남는 어구가 가장 적은 순서를 고른다 */
    static List<Integer> shuffle(int size, long seed) {
        Random random = new Random(seed);
        List<Integer> best = null;
        int bestFixed = Integer.MAX_VALUE;
        for (int t = 0; t < SHUFFLE_TRIES; t++) {
            List<Integer> candidate = IntStream.range(0, size).boxed().collect(Collectors.toCollection(ArrayList::new));
            Collections.shuffle(candidate, random);
            int fixed = (int) IntStream.range(0, size).filter(i -> candidate.get(i) == i).count();
            if (fixed < bestFixed && fixed < size) {
                best = candidate;
                bestFixed = fixed;
                if (fixed == 0) {
                    break;
                }
            }
        }
        return best;
    }
}
