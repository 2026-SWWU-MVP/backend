package com.smwu.backend.material.service;

import com.smwu.backend.material.service.PassageSplitter.SplitPassage;
import com.smwu.backend.material.service.PassageSplitter.SplitResult;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PassageSplitterTest {

    @Test
    void 붙여넣은_텍스트를_구분선으로_나누고_첫_줄_샵은_제목으로_쓴다() {
        List<SplitPassage> passages = PassageSplitter.splitText("""
                # Bringing New Life to Old Cities
                As cities age, neighborhoods can become old and lifeless.

                Second paragraph of the same passage.
                ---
                For years, the neighborhood was known for its high crime rates.
                  -----
                ---

                """);

        assertThat(passages).hasSize(2);
        assertThat(passages.get(0).title()).isEqualTo("Bringing New Life to Old Cities");
        assertThat(passages.get(0).text()).isEqualTo("As cities age, neighborhoods can become old and lifeless.\n\nSecond paragraph of the same passage.");
        assertThat(passages.get(1).title()).isNull();
        assertThat(passages.get(1).text()).isEqualTo("For years, the neighborhood was known for its high crime rates.");
    }

    @Test
    void 구분선이_없으면_지문_하나_대시가_들어간_문장은_나누지_않는다() {
        List<SplitPassage> passages = PassageSplitter.splitText("One passage - with a dash -- and more text.\r\nNext line.");

        assertThat(passages).singleElement().satisfies(p -> {
            assertThat(p.title()).isNull();
            assertThat(p.text()).isEqualTo("One passage - with a dash -- and more text.\nNext line.");
        });
    }

    @Test
    void LLM_결과의_빈_지문을_빼고_긴_지문과_지문_없음은_경고한다() {
        SplitResult cleaned = PassageSplitter.clean(new SplitResult(List.of(
                new SplitPassage("  ", " 20번 ", " Text one. "),
                new SplitPassage(null, null, "   "),
                new SplitPassage("Long", null, "a".repeat(10_001))), null));

        assertThat(cleaned.passages()).hasSize(2);
        assertThat(cleaned.passages().get(0)).isEqualTo(new SplitPassage(null, "20번", "Text one."));
        assertThat(cleaned.warnings()).singleElement().asString().contains("10000자를 넘습니다");

        assertThat(PassageSplitter.clean(new SplitResult(List.of(), List.of())).warnings())
                .containsExactly("영어 지문을 찾지 못했습니다.");
    }

    @Test
    void 지문이_여러_개여도_순서를_유지한다() {
        String text = String.join("\n---\n", Arrays.asList("A text.", "B text.", "C text."));

        assertThat(PassageSplitter.splitText(text)).extracting(SplitPassage::text).containsExactly("A text.", "B text.", "C text.");
    }
}
