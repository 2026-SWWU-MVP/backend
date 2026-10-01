/**
 * LlmClient 인터페이스, OpenAI 구현체(OpenAiLlmClient), Mock 구현체(MockLlmClient).
 * 프롬프트와 응답 스키마는 resources/prompts/{name}.txt, {name}.schema.json (PromptLoader로 로드).
 * Mock 응답은 resources/mock-llm/{task}.json.
 * 관련 이슈: #4
 */
package com.smwu.backend.ai;
