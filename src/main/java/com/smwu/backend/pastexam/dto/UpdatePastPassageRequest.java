package com.smwu.backend.pastexam.dto;

import jakarta.validation.constraints.Size;

/**
 * 추출된 지문 수정 (밑줄 대괄호 보정, 오타 수정 등). 보낸 필드만 바뀐다.
 * title은 빈 문자열을 보내면 지워진다.
 */
public record UpdatePastPassageRequest(
        String title,
        @Size(min = 1, message = "지문 본문은 비울 수 없습니다.") String text
) {
}
