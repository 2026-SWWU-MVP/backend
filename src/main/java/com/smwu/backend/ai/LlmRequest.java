package com.smwu.backend.ai;

import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.Objects;

/**
 * @param task           작업 이름 (예: extract-questions). 로그와 Mock 응답 파일 이름에 사용
 * @param systemPrompt   역할, 절대 규칙
 * @param userPrompt     이번 요청의 입력 (지문, 프로필, 옵션 등)
 * @param files          함께 보낼 파일 (기출 PDF 등). 없으면 빈 목록
 * @param responseSchema 응답 JSON 스키마 (strict 모드: 모든 object에 additionalProperties=false, 모든 필드 required)
 */
public record LlmRequest(
        String task,
        String systemPrompt,
        String userPrompt,
        List<LlmFile> files,
        JsonNode responseSchema
) {

    public LlmRequest {
        Objects.requireNonNull(task, "task");
        Objects.requireNonNull(systemPrompt, "systemPrompt");
        Objects.requireNonNull(userPrompt, "userPrompt");
        Objects.requireNonNull(responseSchema, "responseSchema");
        files = files == null ? List.of() : List.copyOf(files);
    }

    public static LlmRequest of(String task, String systemPrompt, String userPrompt, JsonNode responseSchema) {
        return new LlmRequest(task, systemPrompt, userPrompt, List.of(), responseSchema);
    }

    public LlmRequest withFiles(List<LlmFile> files) {
        return new LlmRequest(task, systemPrompt, userPrompt, files, responseSchema);
    }
}
