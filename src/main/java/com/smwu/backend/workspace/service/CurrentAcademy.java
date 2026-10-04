package com.smwu.backend.workspace.service;

import org.springframework.stereotype.Component;

/**
 * 현재 사용자의 학원.
 * TODO(#6): X-User-Id로 사용자를 찾아 user.academyId를 돌려준다 (학원 소속 전이면 403 NO_ACADEMY).
 * 회원·학원 기능 전까지는 모든 요청을 학원 1번으로 본다.
 */
@Component
public class CurrentAcademy {

    static final long DEFAULT_ACADEMY_ID = 1L;

    public Long academyId() {
        return DEFAULT_ACADEMY_ID;
    }

    /** TODO(#6): 현재 사용자 ID */
    public Long userId() {
        return null;
    }
}
