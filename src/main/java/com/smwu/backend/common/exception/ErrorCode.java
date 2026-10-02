package com.smwu.backend.common.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum ErrorCode {

    // 400
    INVALID_REQUEST(HttpStatus.BAD_REQUEST, "요청 값이 올바르지 않습니다."),
    FILE_TOO_LARGE(HttpStatus.BAD_REQUEST, "업로드 가능한 파일 크기를 초과했습니다."),
    INVALID_FILE(HttpStatus.BAD_REQUEST, "지원하지 않거나 손상된 파일입니다."),

    // 401
    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED, "로그인이 필요합니다."),
    LOGIN_FAILED(HttpStatus.UNAUTHORIZED, "아이디 또는 비밀번호가 일치하지 않습니다."),

    // 403
    OWNER_ONLY(HttpStatus.FORBIDDEN, "원장만 사용할 수 있는 기능입니다."),
    NO_ACADEMY(HttpStatus.FORBIDDEN, "학원에 소속된 후 사용할 수 있습니다."),

    // 404 (다른 학원 리소스도 404로 응답)
    NOT_FOUND(HttpStatus.NOT_FOUND, "요청한 리소스를 찾을 수 없습니다."),

    // 409
    LOGIN_ID_DUPLICATED(HttpStatus.CONFLICT, "이미 사용 중인 아이디입니다."),
    ALREADY_IN_ACADEMY(HttpStatus.CONFLICT, "이미 학원에 소속되어 있습니다."),
    INVITE_CODE_INVALID(HttpStatus.CONFLICT, "만료되었거나 사용할 수 없는 초대 코드입니다."),
    PLAN_LIMIT_EXCEEDED(HttpStatus.CONFLICT, "요금제의 최대 강사 수를 초과했습니다."),
    WORKSPACE_DUPLICATED(HttpStatus.CONFLICT, "이미 같은 학교와 학년의 워크스페이스가 있습니다."),
    PROFILE_NOT_CONFIRMED(HttpStatus.CONFLICT, "확정된 출제 프로필로만 문제를 생성할 수 있습니다."),
    EXTRACTION_IN_PROGRESS(HttpStatus.CONFLICT, "기출 문항을 추출하는 중입니다. 완료 후 다시 시도해 주세요."),
    EXTRACTION_NOT_COMPLETED(HttpStatus.CONFLICT, "기출 문항 추출이 완료된 후에 수정할 수 있습니다."),
    NO_EXTRACTED_PAST_EXAM(HttpStatus.CONFLICT, "추출이 완료된 기출이 없습니다. 기출을 올리고 추출한 뒤 다시 시도해 주세요."),

    // 500
    LLM_ERROR(HttpStatus.BAD_GATEWAY, "AI 응답을 처리하지 못했습니다. 잠시 후 다시 시도해 주세요."),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "서버 오류가 발생했습니다.");

    private final HttpStatus status;
    private final String message;
}
