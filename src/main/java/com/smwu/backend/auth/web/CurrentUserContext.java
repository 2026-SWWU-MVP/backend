package com.smwu.backend.auth.web;

import com.smwu.backend.common.exception.BusinessException;
import com.smwu.backend.common.exception.ErrorCode;
import com.smwu.backend.user.domain.User;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

import java.util.Optional;

/** 서비스 계층에서 현재 요청의 사용자를 꺼낸다 (인터셉터가 담아 둔 값) */
public final class CurrentUserContext {

    private CurrentUserContext() {
    }

    public static Optional<User> find() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes == null) {
            return Optional.empty();
        }
        return Optional.ofNullable((User) attributes.getAttribute(CurrentUserInterceptor.ATTRIBUTE, RequestAttributes.SCOPE_REQUEST));
    }

    /** 작성자 기록용: 현재 사용자 ID, 요청 밖(비동기 작업 등)이면 null */
    public static Long userIdOrNull() {
        return find().map(User::getId).orElse(null);
    }

    /** 요청 밖(비동기 작업 등)이나 헤더 없는 요청이면 401 */
    public static User get() {
        return find().orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHENTICATED));
    }
}
