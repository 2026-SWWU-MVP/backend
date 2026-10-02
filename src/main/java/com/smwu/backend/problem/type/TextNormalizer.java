package com.smwu.backend.problem.type;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 검증용 영문 정규화. 따옴표 모양, 공백, 대괄호 밑줄 표기(①[ ], [ ]) 차이로 같은 문장이 다르다고 판단하지 않게 한다.
 */
public final class TextNormalizer {

    private static final Pattern MARKERS = Pattern.compile("[①-⑳ⓐ-ⓩ]|\\([A-E]\\)(?=\\[)|[\\[\\]]");
    private static final Pattern PUNCTUATION = Pattern.compile("[,.;:!?\"'()\\-–—]");
    private static final Pattern WORD = Pattern.compile("[A-Za-z]+(?:['’-][A-Za-z]+)*");

    private TextNormalizer() {
    }

    /** 따옴표 통일, 밑줄 대괄호 표기 제거, 공백 정리 */
    public static String normalize(String text) {
        if (text == null) {
            return "";
        }
        return MARKERS.matcher(text.replace('’', '\'').replace('‘', '\'').replace('“', '"').replace('”', '"'))
                .replaceAll("")
                .replaceAll("\\s+", " ")
                .strip();
    }

    /** 문장부호까지 지우고 소문자로 (어구 배열 비교용: 조각에는 쉼표·마침표가 없다) */
    public static String normalizeLoose(String text) {
        return PUNCTUATION.matcher(normalize(text).toLowerCase(Locale.ROOT)).replaceAll(" ")
                .replaceAll("\\s+", " ")
                .strip();
    }

    /** 원문에 해당 문장이 (정규화 후) 그대로 들어 있는지 */
    public static boolean containsSentence(String passage, String sentence) {
        String target = normalizeLoose(sentence);
        return !target.isEmpty() && (" " + normalizeLoose(passage) + " ").contains(" " + target + " ");
    }

    /** 원문에 해당 단어가 단어 단위로(대소문자 무시) 들어 있는지 */
    public static boolean containsWord(String passage, String word) {
        String target = word.strip().toLowerCase(Locale.ROOT).replace('’', '\'');
        return words(passage).stream().anyMatch(w -> w.equals(target));
    }

    /** 영단어 목록 (소문자) */
    public static List<String> words(String text) {
        return WORD.matcher(normalize(text)).results()
                .map(m -> m.group().toLowerCase(Locale.ROOT).replace('’', '\''))
                .toList();
    }

    public static int wordCount(String text) {
        return words(text).size();
    }

    /** 한 단어인지 (하이픈·아포스트로피 연결은 한 단어로 본다) */
    public static boolean isSingleWord(String text) {
        String stripped = text == null ? "" : text.strip();
        return WORD.matcher(stripped).matches();
    }

    static List<String> splitSentences(String text) {
        return Arrays.stream(normalize(text).split("(?<=[.!?])\\s+")).map(String::strip).filter(s -> !s.isEmpty()).toList();
    }
}
