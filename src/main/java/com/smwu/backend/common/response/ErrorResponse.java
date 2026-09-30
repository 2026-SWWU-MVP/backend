package com.smwu.backend.common.response;

import com.smwu.backend.common.exception.ErrorCode;

/**
 * 공통 오류 응답. 성공 응답은 DTO를 그대로 반환한다.
 * 예: { "code": "PLAN_LIMIT_EXCEEDED", "message": "요금제의 최대 강사 수(3명)를 초과했습니다." }
 */
public record ErrorResponse(String code, String message) {

    public static ErrorResponse of(ErrorCode errorCode) {
        return new ErrorResponse(errorCode.name(), errorCode.getMessage());
    }

    public static ErrorResponse of(ErrorCode errorCode, String message) {
        return new ErrorResponse(errorCode.name(), message);
    }
}
