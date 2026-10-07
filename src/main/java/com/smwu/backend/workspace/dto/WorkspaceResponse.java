package com.smwu.backend.workspace.dto;

import com.smwu.backend.profile.domain.ProfileStatus;
import com.smwu.backend.workspace.domain.School;
import com.smwu.backend.workspace.domain.Workspace;

import java.time.LocalDateTime;

/**
 * 워크스페이스 카드 (대시보드).
 *
 * @param title   카드 제목 (예: 건대부고 1학년). 별칭이 있으면 첫 별칭을 쓴다
 * @param summary 카드 요약 (기출 회차 수, 프로필 상태, 최근 시험지)
 */
public record WorkspaceResponse(Long id, SchoolResponse school, int grade, String title, Summary summary, LocalDateTime createdAt) {

    /**
     * @param pastExamCount           올린 기출 수
     * @param profileStatus           확정본이 있으면 CONFIRMED, 검토 중인 초안만 있으면 DRAFT, 프로필이 없으면 null
     * @param confirmedProfileVersion 확정된 프로필 버전 (없으면 null)
     * @param latestWorksheet         가장 최근 시험지 (없으면 null)
     */
    public record Summary(long pastExamCount, ProfileStatus profileStatus, Long confirmedProfileId, Integer confirmedProfileVersion,
                          WorksheetRef latestWorksheet) {

        public static final Summary EMPTY = new Summary(0, null, null, null, null);
    }

    public record WorksheetRef(Long id, String title, LocalDateTime createdAt) {
    }

    public static WorkspaceResponse of(Workspace w, School school, Summary summary) {
        String shortName = school.getAliases().isEmpty() ? school.getName() : school.getAliases().get(0);
        return new WorkspaceResponse(w.getId(), SchoolResponse.of(school), w.getGrade(), shortName + " " + w.getGrade() + "학년",
                summary, w.getCreatedAt());
    }
}
