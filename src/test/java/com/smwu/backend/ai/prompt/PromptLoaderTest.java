package com.smwu.backend.ai.prompt;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PromptLoaderTest {

    private final JsonMapper objectMapper = JsonMapper.builder().build();
    private final PromptLoader loader = new PromptLoader(objectMapper);

    @Test
    void 변수를_채워서_프롬프트를_만든다() {
        String prompt = loader.render("test-sample", Map.of("school", "건대부고", "passage", "Price is $5 {{not_a_var}}"));

        assertThat(prompt).isEqualTo("학교: 건대부고\n지문:\nPrice is $5 {{not_a_var}}\n");
    }

    @Test
    void 값이_없는_변수가_있으면_예외() {
        assertThatThrownBy(() -> loader.render("test-sample", Map.of("school", "건대부고")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("passage");
    }

    @Test
    void 프롬프트_파일이_없으면_예외() {
        assertThatThrownBy(() -> loader.text("no-such-prompt"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("prompts/no-such-prompt.txt");
    }

    @Test
    void strict_규칙을_지킨_스키마를_읽는다() {
        assertThat(loader.schema("test-valid").path("type").asString()).isEqualTo("object");
    }

    @Test
    void strict_규칙을_어긴_스키마는_로드할_때_예외() {
        assertThatThrownBy(() -> loader.schema("test-invalid"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("$.items[]: additionalProperties=false 필요")
                .hasMessageContaining("$.note: required에 없음");
    }

    @Test
    void 중첩된_배열_객체의_required_누락도_찾는다() {
        List<String> violations = new ArrayList<>();
        PromptLoader.checkStrict(objectMapper.readTree("""
                {"type":"object","additionalProperties":false,"required":["questions"],
                 "properties":{"questions":{"type":"array","items":{
                   "type":"object","additionalProperties":false,"required":["no"],
                   "properties":{"no":{"type":"integer"},"stem":{"type":["string","null"]}}}}}}
                """), "$", violations);

        assertThat(violations).containsExactly("$.questions[].stem: required에 없음");
    }
}
