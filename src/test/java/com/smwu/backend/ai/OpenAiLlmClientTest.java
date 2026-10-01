package com.smwu.backend.ai;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class OpenAiLlmClientTest {

    private static final String URL = "https://api.test/v1/responses";

    private final ObjectMapper objectMapper = JsonMapper.builder().build();
    private MockRestServiceServer server;
    private OpenAiLlmClient client;

    record Summary(String title, List<String> keywords) {
    }

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new OpenAiLlmClient(builder, objectMapper, "https://api.test/v1", "test-key", "test-model", Duration.ZERO);
    }

    @Test
    void PDF와_스키마를_Responses_API_형식으로_보내고_응답을_변환한다() {
        server.expect(once(), requestTo(URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer test-key"))
                .andExpect(jsonPath("$.model").value("test-model"))
                .andExpect(jsonPath("$.instructions").value("시스템 규칙"))
                .andExpect(jsonPath("$.store").value(false))
                .andExpect(jsonPath("$.input[0].role").value("user"))
                .andExpect(jsonPath("$.input[0].content[0].type").value("input_file"))
                .andExpect(jsonPath("$.input[0].content[0].filename").value("exam.pdf"))
                .andExpect(jsonPath("$.input[0].content[0].file_data").value("data:application/pdf;base64,JVBERi0x"))
                .andExpect(jsonPath("$.input[0].content[1].type").value("input_text"))
                .andExpect(jsonPath("$.input[0].content[1].text").value("문항을 추출하시오"))
                .andExpect(jsonPath("$.text.format.type").value("json_schema"))
                .andExpect(jsonPath("$.text.format.name").value("extract-questions"))
                .andExpect(jsonPath("$.text.format.strict").value(true))
                .andExpect(jsonPath("$.text.format.schema.type").value("object"))
                .andRespond(completed("{\"title\":\"Old Cities\",\"keywords\":[\"revitalize\"]}"));

        LlmResult<Summary> result = client.generate(pdfRequest(), Summary.class);

        assertThat(result.value()).isEqualTo(new Summary("Old Cities", List.of("revitalize")));
        assertThat(result.model()).isEqualTo("test-model-2026");
        assertThat(result.inputTokens()).isEqualTo(120);
        assertThat(result.outputTokens()).isEqualTo(30);
        server.verify();
    }

    @Test
    void 이미지는_input_image_detail_high로_보낸다() {
        server.expect(once(), requestTo(URL))
                .andExpect(jsonPath("$.input[0].content[0].type").value("input_image"))
                .andExpect(jsonPath("$.input[0].content[0].image_url").value("data:image/jpeg;base64,/9j/"))
                .andExpect(jsonPath("$.input[0].content[0].detail").value("high"))
                .andExpect(jsonPath("$.input[0].content[1].type").value("input_text"))
                .andRespond(completed("{\"title\":\"ok\",\"keywords\":[]}"));

        LlmRequest request = textRequest().withFiles(List.of(LlmFile.jpeg("page-1.jpg", new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF})));
        client.generate(request, Summary.class);

        server.verify();
    }

    @Test
    void 응답_JSON_변환에_실패하면_한_번_재시도한다() {
        server.expect(once(), requestTo(URL)).andRespond(completed("{\"title\": 이건 JSON 아님"));
        server.expect(once(), requestTo(URL)).andRespond(completed("{\"title\":\"ok\",\"keywords\":[]}"));

        LlmResult<Summary> result = client.generate(textRequest(), Summary.class);

        assertThat(result.value().title()).isEqualTo("ok");
        server.verify();
    }

    @Test
    void 재시도해도_변환에_실패하면_LlmException() {
        server.expect(once(), requestTo(URL)).andRespond(completed("not json"));
        server.expect(once(), requestTo(URL)).andRespond(completed("still not json"));

        assertThatThrownBy(() -> client.generate(textRequest(), Summary.class))
                .isInstanceOf(LlmException.class)
                .hasMessage("AI 응답을 처리하지 못했습니다. 잠시 후 다시 시도해 주세요.");
        server.verify();
    }

    @Test
    void 요청_한도_초과_429는_재시도한다() {
        server.expect(once(), requestTo(URL)).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));
        server.expect(once(), requestTo(URL)).andRespond(completed("{\"title\":\"ok\",\"keywords\":[]}"));

        assertThat(client.generate(textRequest(), Summary.class).value().title()).isEqualTo("ok");
        server.verify();
    }

    @Test
    void 연결이_끊기면_최대_3번까지_시도한다() {
        server.expect(once(), requestTo(URL)).andRespond(withException(new IOException("Connection reset")));
        server.expect(once(), requestTo(URL)).andRespond(withException(new IOException("Connection reset")));
        server.expect(once(), requestTo(URL)).andRespond(completed("{\"title\":\"ok\",\"keywords\":[]}"));

        assertThat(client.generate(textRequest(), Summary.class).value().title()).isEqualTo("ok");
        server.verify();
    }

    @Test
    void 연결이_3번_끊기면_LlmException() {
        for (int i = 0; i < 3; i++) {
            server.expect(once(), requestTo(URL)).andRespond(withException(new IOException("Connection reset")));
        }

        assertThatThrownBy(() -> client.generate(textRequest(), Summary.class))
                .isInstanceOf(LlmException.class)
                .satisfies(e -> assertThat(((LlmException) e).getDetail()).contains("Connection reset"));
        server.verify();
    }

    @Test
    void 잘못된_요청_400은_재시도하지_않는다() {
        server.expect(once(), requestTo(URL)).andRespond(withStatus(HttpStatus.BAD_REQUEST)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"error\":{\"message\":\"Invalid schema\"}}"));

        assertThatThrownBy(() -> client.generate(textRequest(), Summary.class))
                .isInstanceOf(LlmException.class)
                .satisfies(e -> assertThat(((LlmException) e).getDetail()).contains("HTTP 400", "Invalid schema"));
        server.verify();
    }

    @Test
    void 모델이_거부하면_재시도하지_않고_실패한다() {
        server.expect(once(), requestTo(URL)).andRespond(withSuccess("""
                {"status":"completed","model":"m","output":[{"type":"message","content":[
                  {"type":"refusal","refusal":"I can't help with that."}]}]}
                """, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.generate(textRequest(), Summary.class))
                .isInstanceOf(LlmException.class)
                .satisfies(e -> assertThat(((LlmException) e).getDetail()).contains("거부"));
        server.verify();
    }

    @Test
    void 응답이_잘리면_원인과_함께_실패한다() {
        server.expect(once(), requestTo(URL)).andRespond(withSuccess("""
                {"status":"incomplete","incomplete_details":{"reason":"max_output_tokens"},"output":[]}
                """, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.generate(textRequest(), Summary.class))
                .isInstanceOf(LlmException.class)
                .satisfies(e -> assertThat(((LlmException) e).getDetail()).contains("max_output_tokens"));
        server.verify();
    }

    private LlmRequest textRequest() {
        return LlmRequest.of("summarize", "시스템 규칙", "요약하시오", schema());
    }

    private LlmRequest pdfRequest() {
        return LlmRequest.of("extract-questions", "시스템 규칙", "문항을 추출하시오", schema())
                .withFiles(List.of(LlmFile.pdf("exam.pdf", "%PDF-1".getBytes(StandardCharsets.US_ASCII))));
    }

    private JsonNode schema() {
        return objectMapper.readTree("""
                {"type":"object","additionalProperties":false,"required":["title","keywords"],
                 "properties":{"title":{"type":"string"},"keywords":{"type":"array","items":{"type":"string"}}}}
                """);
    }

    /** 실제 Responses API 응답 형식: output[].content[].output_text 안에 JSON 문자열 */
    private org.springframework.test.web.client.ResponseCreator completed(String outputText) {
        var root = objectMapper.createObjectNode();
        root.put("status", "completed");
        root.put("model", "test-model-2026");
        var message = root.putArray("output").addObject();
        message.put("type", "message");
        message.putArray("content").addObject()
                .put("type", "output_text")
                .put("text", outputText);
        root.putObject("usage").put("input_tokens", 120).put("output_tokens", 30);
        return withSuccess(objectMapper.writeValueAsString(root), MediaType.APPLICATION_JSON);
    }
}
