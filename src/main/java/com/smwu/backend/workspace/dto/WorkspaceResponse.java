package com.smwu.backend.workspace.dto;

import com.smwu.backend.workspace.domain.School;
import com.smwu.backend.workspace.domain.Workspace;

import java.time.LocalDateTime;

/** @param title 카드 제목 (예: 건대부고 1학년). 별칭이 있으면 첫 별칭을 쓴다 */
public record WorkspaceResponse(Long id, SchoolResponse school, int grade, String title, LocalDateTime createdAt) {

    public static WorkspaceResponse of(Workspace w, School school) {
        String shortName = school.getAliases().isEmpty() ? school.getName() : school.getAliases().get(0);
        return new WorkspaceResponse(w.getId(), SchoolResponse.of(school), w.getGrade(), shortName + " " + w.getGrade() + "학년",
                w.getCreatedAt());
    }
}
