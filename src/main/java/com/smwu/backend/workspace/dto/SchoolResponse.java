package com.smwu.backend.workspace.dto;

import com.smwu.backend.workspace.domain.School;

import java.util.List;

public record SchoolResponse(Long id, String name, String region, List<String> aliases) {

    public static SchoolResponse of(School s) {
        return new SchoolResponse(s.getId(), s.getName(), s.getRegion(), List.copyOf(s.getAliases()));
    }
}
