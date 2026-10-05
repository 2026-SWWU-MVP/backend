package com.smwu.backend.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record SignupRequest(
        @NotBlank(message = "아이디를 입력해 주세요.")
        @Pattern(regexp = "^[a-z0-9]{4,20}$", message = "아이디는 영문 소문자와 숫자 4~20자입니다.")
        String loginId,
        @NotBlank(message = "비밀번호를 입력해 주세요.")
        @Size(min = 4, max = 100, message = "비밀번호는 4자 이상입니다.")
        String password,
        @NotBlank(message = "이름을 입력해 주세요.")
        @Size(max = 30, message = "이름은 30자 이하입니다.")
        String name
) {
}
