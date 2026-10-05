package com.smwu.backend.auth.dto;

import com.smwu.backend.user.domain.User;
import com.smwu.backend.user.domain.UserRole;

/**
 * 로그인 응답과 내 정보. 프론트는 userId를 저장해 두고 이후 모든 요청 헤더에 X-User-Id로 넣는다.
 *
 * @param role      학원 소속 전이면 null (학원 만들기 / 초대 코드 입력 화면으로)
 * @param academyId 학원 소속 전이면 null
 */
public record UserResponse(Long userId, String loginId, String name, UserRole role, Long academyId) {

    public static UserResponse of(User user) {
        return new UserResponse(user.getId(), user.getLoginId(), user.getName(), user.getRole(), user.getAcademyId());
    }
}
