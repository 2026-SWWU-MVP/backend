package com.smwu.backend.material.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 지문 텍스트 붙여넣기. 여러 지문은 "---"만 있는 줄로 구분하고, 지문 첫 줄을 "# 제목"으로 쓰면 제목이 된다.
 * <pre>
 * # Bringing New Life to Old Cities
 * As cities age, neighborhoods can become old and lifeless, ...
 * ---
 * For years, the neighborhood was known for its high crime rates. ...
 * </pre>
 */
public record TextMaterialRequest(
        @NotBlank(message = "자료 이름을 입력해 주세요.")
        @Size(max = 200, message = "자료 이름은 200자 이하입니다.")
        String title,

        @NotBlank(message = "지문을 입력해 주세요.")
        @Size(max = 50_000, message = "한 번에 50,000자까지 붙여넣을 수 있습니다.")
        String text
) {
}
