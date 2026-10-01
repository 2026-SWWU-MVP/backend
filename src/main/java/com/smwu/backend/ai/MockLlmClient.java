package com.smwu.backend.ai;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * LLM을 호출하지 않고 {@code resources/mock-llm/{task}.json}의 고정 응답을 돌려준다.
 * API 키 없이 서버를 띄우거나(프론트 연동), 테스트할 때 사용한다. (LLM_PROVIDER=mock, 기본값)
 */
@Slf4j
public class MockLlmClient implements LlmClient {

    static final String MOCK_MODEL = "mock";

    private final ObjectMapper objectMapper;

    public MockLlmClient(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public <T> LlmResult<T> generate(LlmRequest request, Class<T> responseType) {
        String path = "mock-llm/" + request.task() + ".json";
        ClassPathResource resource = new ClassPathResource(path);
        if (!resource.exists()) {
            throw new LlmException("Mock 응답 파일이 없음: resources/" + path);
        }

        try (InputStream in = resource.getInputStream()) {
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            T value = objectMapper.readValue(text, responseType);
            log.info("LLM [{}] Mock 응답 반환", request.task());
            return new LlmResult<>(value, text, MOCK_MODEL, 0, 0);
        } catch (IOException | JacksonException e) {
            throw new LlmException("Mock 응답을 " + responseType.getSimpleName() + "로 변환하지 못함: " + path, e);
        }
    }
}
