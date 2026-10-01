package com.smwu.backend.ai;

import com.smwu.backend.common.config.AppProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;

@Slf4j
@Configuration
public class LlmConfig {

    private static final Duration RETRY_BACKOFF = Duration.ofSeconds(2);

    /** app.llm.provider 값에 따라 실제 OpenAI 구현체 또는 Mock을 등록한다 */
    @Bean
    public LlmClient llmClient(AppProperties appProperties, ObjectMapper objectMapper) {
        AppProperties.Llm llm = appProperties.llm();
        String provider = llm.provider() == null || llm.provider().isBlank()
                ? "mock" : llm.provider().trim().toLowerCase();

        return switch (provider) {
            case "mock" -> {
                log.info("LLM provider: mock (resources/mock-llm/*.json 고정 응답)");
                yield new MockLlmClient(objectMapper);
            }
            case "openai" -> {
                requireText(llm.apiKey(), "LLM_API_KEY");
                requireText(llm.model(), "LLM_MODEL");
                log.info("LLM provider: openai (model={})", llm.model());
                yield new OpenAiLlmClient(OpenAiLlmClient.restClientBuilder(Duration.ofSeconds(llm.timeoutSeconds())), objectMapper,
                        llm.baseUrl(), llm.apiKey(), llm.model(), RETRY_BACKOFF);
            }
            default -> throw new IllegalStateException(
                    "지원하지 않는 LLM_PROVIDER: " + llm.provider() + " (openai 또는 mock)");
        };
    }

    private static void requireText(String value, String envName) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("LLM_PROVIDER=openai 이면 " + envName + " 환경변수가 필요합니다.");
        }
    }
}
