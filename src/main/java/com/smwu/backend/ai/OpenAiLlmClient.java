package com.smwu.backend.ai;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Base64;
import java.util.Set;

/**
 * OpenAI Responses API(POST /responses) 구현체.
 * <ul>
 *   <li>PDF는 {@code input_file}, 이미지는 {@code input_image}(detail=high)에 base64 data URL로 넣는다.
 *       텍스트 레이어가 있는 PDF는 텍스트만 전달되므로 밑줄을 봐야 하면 페이지 이미지를 함께 보낸다</li>
 *   <li>응답은 {@code text.format = json_schema (strict)}로 강제한다</li>
 *   <li>429/5xx/JSON 변환 실패는 1회, 네트워크 오류(연결 끊김·타임아웃)는 2회 재시도. 거부(refusal)와 4xx는 바로 실패</li>
 *   <li>시험지 내용이 OpenAI에 저장되지 않도록 {@code store: false}</li>
 * </ul>
 */
@Slf4j
public class OpenAiLlmClient implements LlmClient {

    static final int MAX_ATTEMPTS = 2;
    /** 큰 요청(시험지 이미지 수 MB)은 연결이 끊기는 경우가 있어 네트워크 오류는 한 번 더 시도한다 */
    static final int MAX_NETWORK_ATTEMPTS = 3;
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Set<Integer> RETRYABLE_STATUS = Set.of(408, 429, 500, 502, 503, 504);
    private static final int ERROR_BODY_LOG_LIMIT = 500;

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final String model;
    private final Duration retryBackoff;

    public OpenAiLlmClient(RestClient.Builder restClientBuilder, ObjectMapper objectMapper,
                           String baseUrl, String apiKey, String model, Duration retryBackoff) {
        this.restClient = restClientBuilder
                .baseUrl(baseUrl)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .build();
        this.objectMapper = objectMapper;
        this.model = model;
        this.retryBackoff = retryBackoff;
    }

    /**
     * OpenAI 호출용 RestClient.Builder. HTTP/1.1로 고정한다.
     * JDK HttpClient의 HTTP/2로 수 MB 요청을 보내면 Connection reset이 나고, 끊긴 연결을 재사용해 재시도까지 바로 실패했다 (이슈 #5 실험)
     */
    public static RestClient.Builder restClientBuilder(Duration readTimeout) {
        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(CONNECT_TIMEOUT)
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(readTimeout);
        return RestClient.builder().requestFactory(requestFactory);
    }

    @Override
    public <T> LlmResult<T> generate(LlmRequest request, Class<T> responseType) {
        String requestBody = objectMapper.writeValueAsString(buildRequestBody(request));

        for (int attempt = 1; ; attempt++) {
            long startedAt = System.currentTimeMillis();
            try {
                String responseBody = restClient.post()
                        .uri("/responses")
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(requestBody)
                        .retrieve()
                        .body(String.class);

                JsonNode response = objectMapper.readTree(responseBody);
                String outputText = extractOutputText(response);
                T value = objectMapper.readValue(outputText, responseType);

                JsonNode usage = response.path("usage");
                LlmResult<T> result = new LlmResult<>(value, outputText, response.path("model").asString(model),
                        usage.path("input_tokens").asInt(), usage.path("output_tokens").asInt());
                log.info("LLM [{}] 완료 attempt={} {}ms tokens(in={}, out={})", request.task(), attempt,
                        System.currentTimeMillis() - startedAt, result.inputTokens(), result.outputTokens());
                return result;

            } catch (HttpStatusCodeException e) {
                String detail = "HTTP " + e.getStatusCode().value() + " " + truncate(e.getResponseBodyAsString());
                if (!RETRYABLE_STATUS.contains(e.getStatusCode().value()) || attempt >= MAX_ATTEMPTS) {
                    throw fail(request, detail, e);
                }
                logRetry(request, attempt, detail);
            } catch (ResourceAccessException e) {
                if (attempt >= MAX_NETWORK_ATTEMPTS) {
                    throw fail(request, "연결 실패 또는 타임아웃: " + e.getMessage(), e);
                }
                logRetry(request, attempt, e.getMessage());
            } catch (JacksonException e) {
                if (attempt >= MAX_ATTEMPTS) {
                    throw fail(request, "응답 JSON을 " + responseType.getSimpleName() + "로 변환하지 못함: " + e.getOriginalMessage(), e);
                }
                logRetry(request, attempt, "JSON 변환 실패: " + e.getOriginalMessage());
            }
            sleep(retryBackoff.multipliedBy(attempt));
        }
    }

    ObjectNode buildRequestBody(LlmRequest request) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", model);
        body.put("instructions", request.systemPrompt());
        body.put("store", false);

        ObjectNode userMessage = body.putArray("input").addObject();
        userMessage.put("role", "user");
        ArrayNode content = userMessage.putArray("content");
        for (LlmFile file : request.files()) {
            String dataUrl = "data:" + file.mimeType() + ";base64," + Base64.getEncoder().encodeToString(file.content());
            if (file.isImage()) {
                // 시험지 밑줄·작은 글씨를 읽어야 하므로 high
                content.addObject()
                        .put("type", "input_image")
                        .put("image_url", dataUrl)
                        .put("detail", "high");
            } else {
                content.addObject()
                        .put("type", "input_file")
                        .put("filename", file.filename())
                        .put("file_data", dataUrl);
            }
        }
        content.addObject()
                .put("type", "input_text")
                .put("text", request.userPrompt());

        ObjectNode format = body.putObject("text").putObject("format");
        format.put("type", "json_schema");
        format.put("name", schemaName(request.task()));
        format.set("schema", request.responseSchema());
        format.put("strict", true);
        return body;
    }

    /** output[].content[] 중 output_text를 모은다. 거부(refusal)나 잘린 응답은 재시도하지 않고 실패 처리 */
    private String extractOutputText(JsonNode response) {
        String status = response.path("status").asString("");
        if ("incomplete".equals(status)) {
            String reason = response.path("incomplete_details").path("reason").asString("unknown");
            throw new LlmException("응답이 완료되지 않음 (reason=" + reason + ")");
        }
        if (!"completed".equals(status)) {
            throw new LlmException("예상하지 못한 응답 상태: " + status + " " + truncate(response.path("error").toString()));
        }

        StringBuilder text = new StringBuilder();
        for (JsonNode item : response.path("output")) {
            if (!"message".equals(item.path("type").asString(""))) {
                continue;
            }
            for (JsonNode part : item.path("content")) {
                String type = part.path("type").asString("");
                if ("refusal".equals(type)) {
                    throw new LlmException("모델이 응답을 거부함: " + part.path("refusal").asString(""));
                }
                if ("output_text".equals(type)) {
                    text.append(part.path("text").asString(""));
                }
            }
        }
        if (text.isEmpty()) {
            throw new LlmException("응답에 output_text가 없음");
        }
        return text.toString();
    }

    private LlmException fail(LlmRequest request, String detail, Throwable cause) {
        log.error("LLM [{}] 실패: {}", request.task(), detail);
        return new LlmException(detail, cause);
    }

    private void logRetry(LlmRequest request, int attempt, String detail) {
        log.warn("LLM [{}] attempt={} 실패, 재시도: {}", request.task(), attempt, detail);
    }

    /** json_schema name은 영문, 숫자, _, - 만 허용 (최대 64자) */
    private static String schemaName(String task) {
        String name = task.replaceAll("[^A-Za-z0-9_-]", "_");
        return name.length() > 64 ? name.substring(0, 64) : name;
    }

    private static String truncate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() > ERROR_BODY_LOG_LIMIT ? text.substring(0, ERROR_BODY_LOG_LIMIT) + "..." : text;
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmException("재시도 대기 중 인터럽트", e);
        }
    }
}
