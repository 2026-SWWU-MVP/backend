package com.smwu.backend.auth.web;

import com.smwu.backend.common.exception.BusinessException;
import com.smwu.backend.common.exception.ErrorCode;
import com.smwu.backend.user.domain.User;
import com.smwu.backend.user.repository.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * X-User-Id 헤더로 현재 사용자를 찾아 요청에 담는다 (/api/auth/** 제외). 없거나 모르는 사용자면 401.
 * 헤더 값은 누구나 바꿀 수 있으므로 MVP 시연용이다 (설계서 3.6, 추후 JWT로 전환).
 */
@Component
@RequiredArgsConstructor
public class CurrentUserInterceptor implements HandlerInterceptor {

    public static final String HEADER = "X-User-Id";
    static final String ATTRIBUTE = CurrentUserInterceptor.class.getName() + ".user";

    private final UserRepository userRepository;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        String header = request.getHeader(HEADER);
        Long userId = parse(header);
        User user = userId == null ? null : userRepository.findById(userId).orElse(null);
        if (user == null) {
            throw new BusinessException(ErrorCode.UNAUTHENTICATED);
        }
        request.setAttribute(ATTRIBUTE, user);
        return true;
    }

    private static Long parse(String header) {
        if (header == null || header.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(header.strip());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
