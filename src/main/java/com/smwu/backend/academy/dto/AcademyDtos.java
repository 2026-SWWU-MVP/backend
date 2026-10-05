package com.smwu.backend.academy.dto;

import com.smwu.backend.academy.domain.Academy;
import com.smwu.backend.academy.domain.AcademyPlan;
import com.smwu.backend.academy.domain.InviteCode;
import com.smwu.backend.user.domain.User;
import com.smwu.backend.user.domain.UserRole;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;

/** 학원·강사·초대 코드 API 요청과 응답 */
public final class AcademyDtos {

    private AcademyDtos() {
    }

    public record AcademyRequest(
            @NotBlank(message = "학원 이름을 입력해 주세요.")
            @Size(max = 50, message = "학원 이름은 50자 이하입니다.")
            String name
    ) {
    }

    public record JoinRequest(
            @NotBlank(message = "초대 코드를 입력해 주세요.")
            String code
    ) {
    }

    /**
     * @param teacherCount 원장을 뺀 강사 수
     * @param maxTeachers  요금제의 최대 강사 수
     */
    public record AcademyResponse(Long id, String name, AcademyPlan plan, int teacherCount, int maxTeachers,
                                  LocalDateTime createdAt) {

        public static AcademyResponse of(Academy a, long teacherCount) {
            return new AcademyResponse(a.getId(), a.getName(), a.getPlan(), (int) teacherCount, a.getPlan().maxTeachers(),
                    a.getCreatedAt());
        }
    }

    public record MemberResponse(Long userId, String loginId, String name, UserRole role) {

        public static MemberResponse of(User u) {
            return new MemberResponse(u.getId(), u.getLoginId(), u.getName(), u.getRole());
        }
    }

    /** @param usedByName 사용한 강사 이름 (사용 전이면 null) */
    public record InviteResponse(Long id, String code, InviteCode.Status status, LocalDateTime expiresAt, String usedByName,
                                 LocalDateTime usedAt, LocalDateTime createdAt) {

        public static InviteResponse of(InviteCode c, LocalDateTime now, String usedByName) {
            return new InviteResponse(c.getId(), c.getCode(), c.status(now), c.getExpiresAt(), usedByName, c.getUsedAt(),
                    c.getCreatedAt());
        }
    }
}
