package com.smwu.backend.material.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

/**
 * 지문 추가·수정. 수정할 때는 보낸 필드만 바뀐다 (title, sourceLabel은 빈 문자열이면 지워짐).
 * 추가할 때는 content가 필수이고, orderNo가 없으면 맨 뒤에 붙는다.
 */
public record PassageRequest(
        @Size(max = 300, message = "제목은 300자 이하입니다.") String title,
        @Size(max = 200, message = "출처는 200자 이하입니다.") String sourceLabel,
        @Size(min = 1, max = 10_000, message = "지문은 1~10,000자입니다.") String content,
        @Min(value = 1, message = "순서는 1 이상입니다.") Integer orderNo
) {
}
