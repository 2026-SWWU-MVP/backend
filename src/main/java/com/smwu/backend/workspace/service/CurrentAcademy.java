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
}
