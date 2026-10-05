package com.smwu.backend.workspace.service;

import com.smwu.backend.auth.web.CurrentUserContext;
import com.smwu.backend.common.exception.BusinessException;
import com.smwu.backend.common.exception.ErrorCode;
import com.smwu.backend.user.domain.User;
import org.springframework.stereotype.Component;

/** 현재 사용자(X-User-Id)의 학원. 학원 소속 전이면 403 NO_ACADEMY */
@Component
public class CurrentAcademy {

    public Long academyId() {
        Long academyId = CurrentUserContext.get().getAcademyId();
        if (academyId == null) {
            throw new BusinessException(ErrorCode.NO_ACADEMY);
        }
        return academyId;
    }

    public Long userId() {
        return CurrentUserContext.get().getId();
    }

    public User user() {
        return CurrentUserContext.get();
    }

    /** 원장 전용 기능 (설계서 3.4). 소속 전이면 403 NO_ACADEMY, 강사면 403 OWNER_ONLY */
    public void requireOwner() {
        academyId();
        if (!CurrentUserContext.get().isOwner()) {
            throw new BusinessException(ErrorCode.OWNER_ONLY);
        }
    }
}
