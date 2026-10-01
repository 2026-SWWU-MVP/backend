package com.smwu.backend.ai;

/**
 * LLM 호출 진입점. 서비스 계층은 이 인터페이스만 사용하고, 실제 제공사(OpenAI)나 Mock은 설정으로 바꾼다.
 * 응답은 {@link LlmRequest#responseSchema()}로 강제한 JSON을 {@code responseType}으로 변환해서 돌려준다.
 */
public interface LlmClient {

    /**
     * @throws LlmException 호출 실패, 거부(refusal), 응답이 잘림, 재시도 후에도 JSON 변환 실패
     */
    <T> LlmResult<T> generate(LlmRequest request, Class<T> responseType);
}
