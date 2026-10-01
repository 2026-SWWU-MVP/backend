package com.smwu.backend.ai;

/**
 * @param value        응답 JSON을 변환한 객체
 * @param rawText      모델이 돌려준 JSON 원문. 디버깅용으로 저장할 때 사용하고 화면 데이터와 섞지 않는다
 * @param model        실제로 응답한 모델 (재현성 기록용)
 * @param inputTokens  입력 토큰 수 (Mock은 0)
 * @param outputTokens 출력 토큰 수 (Mock은 0)
 */
public record LlmResult<T>(T value, String rawText, String model, int inputTokens, int outputTokens) {
}
