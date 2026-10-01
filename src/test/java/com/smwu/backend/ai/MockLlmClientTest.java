package com.smwu.backend.ai;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MockLlmClientTest {

    private final ObjectMapper objectMapper = JsonMapper.builder().build();
    private final MockLlmClient client = new MockLlmClient(objectMapper);

    record Summary(String title, List<String> keywords) {
    }

    @Test
    void task_이름의_Mock_응답_파일을_변환해서_돌려준다() {
        LlmResult<Summary> result = client.generate(request("test-summary"), Summary.class);

        assertThat(result.value()).isEqualTo(new Summary("Bringing New Life to Old Cities", List.of("revitalize", "renovate")));
        assertThat(result.model()).isEqualTo("mock");
    }

    @Test
    void Mock_응답_파일이_없으면_LlmException() {
        assertThatThrownBy(() -> client.generate(request("no-such-task"), Summary.class))
                .isInstanceOf(LlmException.class)
                .satisfies(e -> assertThat(((LlmException) e).getDetail()).contains("mock-llm/no-such-task.json"));
    }

    private LlmRequest request(String task) {
        return LlmRequest.of(task, "system", "user", objectMapper.createObjectNode());
    }
}
