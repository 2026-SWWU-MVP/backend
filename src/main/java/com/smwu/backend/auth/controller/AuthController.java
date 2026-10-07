package com.smwu.backend.auth.controller;

import com.smwu.backend.auth.dto.LoginRequest;
import com.smwu.backend.auth.dto.SignupRequest;
import com.smwu.backend.auth.dto.UserResponse;
import com.smwu.backend.auth.service.AuthService;
import com.smwu.backend.auth.web.CurrentUser;
import com.smwu.backend.user.domain.User;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 가입·로그인. /api/auth/** 는 X-User-Id 없이 호출한다.
 * 로그인 응답의 userId를 이후 모든 요청 헤더 X-User-Id에 넣는다.
 */
@Tag(name = "01. 계정", description = "회원가입·로그인. 로그인 응답의 userId를 이후 모든 요청 헤더 X-User-Id에 넣는다")
@RestController
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @Operation(summary = "아이디 중복 확인")
    @GetMapping("/api/auth/check-login-id")
    public Map<String, Boolean> checkLoginId(@RequestParam String loginId) {
        return Map.of("available", authService.isLoginIdAvailable(loginId));
    }

    /** 중복 아이디면 409 LOGIN_ID_DUPLICATED. 가입 직후에는 학원 소속이 없다 */
    @Operation(summary = "회원가입", description = "중복 아이디면 409 LOGIN_ID_DUPLICATED. 가입 직후에는 학원 소속이 없다")
    @PostMapping("/api/auth/signup")
    @ResponseStatus(HttpStatus.CREATED)
    public UserResponse signup(@Valid @RequestBody SignupRequest request) {
        return authService.signup(request);
    }

    /** 아이디나 비밀번호가 틀리면 401 LOGIN_FAILED */
    @Operation(summary = "로그인", description = "아이디나 비밀번호가 틀리면 401 LOGIN_FAILED")
    @PostMapping("/api/auth/login")
    public UserResponse login(@Valid @RequestBody LoginRequest request) {
        return authService.login(request);
    }

    @Operation(summary = "내 정보")
    @GetMapping("/api/me")
    public UserResponse me(@CurrentUser User user) {
        return UserResponse.of(user);
    }
}
