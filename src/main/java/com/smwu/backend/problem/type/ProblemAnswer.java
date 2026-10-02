package com.smwu.backend.problem.type;

import java.util.List;

/**
 * 유형별 정답 구조. 해당 유형에서 쓰지 않는 필드는 null.
 *
 * @param blanks      요약문 빈칸: 빈칸 순서대로의 정답 단어
 * @param sentence    어구 배열·조건 영작: 정답 문장 (원문 그대로)
 * @param chunkOrder  어구 배열: 정답 순서대로 나열한 [보기] 항목의 위치 (0부터)
 * @param corrections 어법 오류 수정: 틀린 곳의 번호와 고친 표현
 */
public record ProblemAnswer(List<String> blanks, String sentence, List<Integer> chunkOrder, List<Correction> corrections) {

    /**
     * @param number    밑줄 번호 (1~5)
     * @param shown     문제에 보인 틀린 표현
     * @param corrected 바르게 고친 표현 (원문)
     */
    public record Correction(int number, String shown, String corrected) {
    }

    public static ProblemAnswer blanks(List<String> blanks) {
        return new ProblemAnswer(List.copyOf(blanks), null, null, null);
    }

    public static ProblemAnswer ordering(String sentence, List<Integer> chunkOrder) {
        return new ProblemAnswer(null, sentence, List.copyOf(chunkOrder), null);
    }

    public static ProblemAnswer sentence(String sentence) {
        return new ProblemAnswer(null, sentence, null, null);
    }

    public static ProblemAnswer corrections(List<Correction> corrections) {
        return new ProblemAnswer(null, null, null, List.copyOf(corrections));
    }
}
