package com.smwu.backend.profile.dto;

import com.smwu.backend.profile.domain.ProfileOrigin;
import com.smwu.backend.profile.domain.ProfileStatus;
import com.smwu.backend.profile.domain.SchoolProfile;

import java.time.LocalDateTime;

/** 프로필 버전 목록용 요약 */
public record ProfileSummaryResponse(
        Long id,
        int version,
        ProfileStatus status,
        ProfileOrigin origin,
        int examCount,
        int ruleCount,
        LocalDateTime createdAt,
        LocalDateTime confirmedAt
) {

    public static ProfileSummaryResponse of(SchoolProfile profile) {
        return new ProfileSummaryResponse(profile.getId(), profile.getVersion(), profile.getStatus(), profile.getOrigin(),
                profile.getSourceExamIds().size(), profile.getRules().size(), profile.getCreatedAt(), profile.getConfirmedAt());
    }
}
