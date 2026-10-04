package com.smwu.backend.workspace.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 학교 등록 (검색해도 없을 때).
 *
 * @param region  예: 서울 강남구
 * @param aliases 줄임말 (예: 건대부고)
 */
public record SchoolRequest(
        @NotBlank(message = "학교 이름을 입력해 주세요.")
        @Size(max = 100, message = "학교 이름은 100자 이하입니다.")
        String name,
        @Size(max = 100, message = "지역은 100자 이하입니다.")
        String region,
        @Size(max = 10, message = "별칭은 10개까지 등록할 수 있습니다.")
        List<@NotBlank @Size(max = 50) String> aliases
) {
}
