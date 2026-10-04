package com.smwu.backend.workspace.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record WorkspaceRequest(
        @NotNull(message = "학교를 선택해 주세요.")
        Long schoolId,
        @NotNull(message = "학년을 선택해 주세요.")
        @Min(value = 1, message = "학년은 1~3입니다.")
        @Max(value = 3, message = "학년은 1~3입니다.")
        Integer grade
) {
}
