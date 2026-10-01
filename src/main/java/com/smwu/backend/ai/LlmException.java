package com.smwu.backend.ai;

import com.smwu.backend.common.exception.BusinessException;
import com.smwu.backend.common.exception.ErrorCode;
import lombok.Getter;

/**
 * LLM 호출 실패. 클라이언트에는 공통 메시지(LLM_ERROR)만 내려가고, 원인은 {@link #getDetail()}로 로그에 남긴다.
 */
@Getter
public class LlmException extends BusinessException {

    private final String detail;

    public LlmException(String detail) {
        this(detail, null);
    }

    public LlmException(String detail, Throwable cause) {
        super(ErrorCode.LLM_ERROR);
        this.detail = detail;
        if (cause != null) {
            initCause(cause);
        }
    }

    @Override
    public String toString() {
        return "LlmException: " + detail;
    }
}
