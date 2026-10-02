package com.smwu.backend.profile.controller;

import com.smwu.backend.profile.dto.ProfileResponse;
import com.smwu.backend.profile.dto.ProfileSummaryResponse;
import com.smwu.backend.profile.service.ProfileAnalysisService;
import com.smwu.backend.profile.service.ProfileQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 출제 프로필 생성·조회. 확정·AI 재검토·강사 의견·직접 수정은 #13.
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class ProfileController {

    private final ProfileAnalysisService analysisService;
    private final ProfileQueryService queryService;

    /**
     * 워크스페이스의 추출 완료 기출로 새 프로필 버전(DRAFT)을 만든다.
     * 규칙 요약에 LLM을 호출하므로 수십 초 걸릴 수 있다 (프론트는 로딩 표시).
     */
    @PostMapping("/workspaces/{workspaceId}/profiles")
    @ResponseStatus(HttpStatus.CREATED)
    public ProfileResponse create(@PathVariable Long workspaceId) {
        return analysisService.createFromPastExams(workspaceId);
    }

    @GetMapping("/workspaces/{workspaceId}/profiles")
    public List<ProfileSummaryResponse> list(@PathVariable Long workspaceId) {
        return queryService.list(workspaceId);
    }

    @GetMapping("/profiles/{profileId}")
    public ProfileResponse get(@PathVariable Long profileId) {
        return queryService.get(profileId);
    }
}
