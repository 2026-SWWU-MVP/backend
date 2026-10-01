package com.smwu.backend.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/** application.properties의 app.* 설정 */
@ConfigurationProperties(prefix = "app")
public record AppProperties(Storage storage, Cors cors, Llm llm) {

    /** 업로드 파일 저장 루트 경로 */
    public record Storage(String root) {
    }

    /** 프론트 로컬 주소 */
    public record Cors(List<String> allowedOrigins) {
    }

    /**
     * LLM API 설정. 키는 환경변수 LLM_API_KEY로만 주입
     *
     * @param provider       openai(실제 호출) / mock(고정 응답, 기본값)
     * @param timeoutSeconds 응답 대기 시간. PDF 분석은 오래 걸릴 수 있어 넉넉하게 둔다
     */
    public record Llm(String provider, String apiKey, String model, String baseUrl, int timeoutSeconds) {
    }
}
