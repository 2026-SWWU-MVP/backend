package com.smwu.backend.problem.type;

/**
 * 유형별 생성 옵션. null이면 기본값을 쓴다 ({@link #withDefaults()}).
 *
 * @param blankCount      요약문 빈칸: 빈칸 수 (1~6, 기본 2)
 * @param firstLetterHint 요약문 빈칸: 첫 철자 제시 여부 (기본 false).
 *                        false면 "윗글에 나온 단어를 형태 변경 없이", true면 "제시된 첫 철자 + 문맥에 맞는 형태"
 * @param minWords        어구 배열: 대상 문장의 최소 단어 수 (기본 12)
 * @param errorCount      어법 오류 수정: 밑줄 5곳 중 틀린 곳 수 (1~2, 기본 1)
 */
public record ProblemOptions(Integer blankCount, Boolean firstLetterHint, Integer minWords, Integer errorCount) {

    public static final int DEFAULT_BLANK_COUNT = 2;
    public static final int MAX_BLANK_COUNT = 6;
    public static final int DEFAULT_MIN_WORDS = 12;
    public static final int DEFAULT_ERROR_COUNT = 1;
    public static final int MAX_ERROR_COUNT = 2;

    /** 요약문 빈칸·어구 배열용 (어법 오류 수 기본값) */
    public ProblemOptions(Integer blankCount, Boolean firstLetterHint, Integer minWords) {
        this(blankCount, firstLetterHint, minWords, null);
    }

    public static ProblemOptions defaults() {
        return new ProblemOptions(null, null, null, null).withDefaults();
    }

    public ProblemOptions withDefaults() {
        return new ProblemOptions(
                blankCount == null ? DEFAULT_BLANK_COUNT : Math.max(1, Math.min(MAX_BLANK_COUNT, blankCount)),
                firstLetterHint != null && firstLetterHint,
                minWords == null ? DEFAULT_MIN_WORDS : Math.max(5, Math.min(40, minWords)),
                errorCount == null ? DEFAULT_ERROR_COUNT : Math.max(1, Math.min(MAX_ERROR_COUNT, errorCount)));
    }
}
