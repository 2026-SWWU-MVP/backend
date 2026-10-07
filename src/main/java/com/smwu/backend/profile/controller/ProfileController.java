package com.smwu.backend.profile.controller;

import com.smwu.backend.profile.dto.FeedbackRequest;
import com.smwu.backend.profile.dto.ManualEditRequest;
import com.smwu.backend.profile.dto.ProfileResponse;
import com.smwu.backend.profile.dto.ProfileSummaryResponse;
import com.smwu.backend.profile.service.ProfileAnalysisService;
import com.smwu.backend.profile.service.ProfileQueryService;
import com.smwu.backend.profile.service.ProfileRevisionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 출제 프로필 생성·조회와 강사 검토 루프.
 * 강사는 DRAFT 프로필을 보고 ① 확정 ② AI 재검토 ③ 의견 입력 ④ 직접 수정 중 하나를 고른다.
 * ②~④는 새 DRAFT 버전을 돌려주며, changeSummary에 이전 버전 대비 바뀐 점이 들어 있다.
 */
@Tag(name = "05. 출제 프로필", description = "기출 + 학교 DB로 출제 프로필 생성 → 강사 검토(재검토·의견·직접 수정) → 확정")
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class ProfileController {

    private final ProfileAnalysisService analysisService;
    private final ProfileQueryService queryService;
    private final ProfileRevisionService revisionService;

    /**
     * 워크스페이스의 추출 완료 기출로 새 프로필 버전(DRAFT)을 만든다.
     * 규칙 요약에 LLM을 호출하므로 수십 초 걸릴 수 있다 (프론트는 로딩 표시).
     */
    @Operation(summary = "출제 프로필 생성 (분석)", description = "워크스페이스의 추출 완료 기출로 새 프로필 버전(DRAFT)을 만든다. 규칙 요약에 LLM을 호출하므로 수십 초 걸릴 수 있다 (프론트는 로딩 표시).")
    @PostMapping("/workspaces/{workspaceId}/profiles")
    @ResponseStatus(HttpStatus.CREATED)
    public ProfileResponse create(@PathVariable Long workspaceId) {
        return analysisService.createFromPastExams(workspaceId);
    }

    @Operation(summary = "프로필 버전 목록")
    @GetMapping("/workspaces/{workspaceId}/profiles")
    public List<ProfileSummaryResponse> list(@PathVariable Long workspaceId) {
        return queryService.list(workspaceId);
    }

    /** 현재 확정 프로필 (문제 생성에 쓰는 버전). 없으면 404 */
    @Operation(summary = "현재 확정 프로필", description = "현재 확정 프로필 (문제 생성에 쓰는 버전). 없으면 404")
    @GetMapping("/workspaces/{workspaceId}/profiles/confirmed")
    public ProfileResponse confirmed(@PathVariable Long workspaceId) {
        return revisionService.getConfirmed(workspaceId);
    }

    @Operation(summary = "프로필 상세")
    @GetMapping("/profiles/{profileId}")
    public ProfileResponse get(@PathVariable Long profileId) {
        return queryService.get(profileId);
    }

    /** ① 확정 (OK). 기존 확정본은 SUPERSEDED */
    @Operation(summary = "프로필 확정", description = "① 확정 (OK). 기존 확정본은 SUPERSEDED")
    @PostMapping("/profiles/{profileId}/confirm")
    public ProfileResponse confirm(@PathVariable Long profileId) {
        return revisionService.confirm(profileId);
    }

    /** ② AI 재검토 → 새 DRAFT. LLM 호출로 수십 초 걸릴 수 있다 */
    @Operation(summary = "AI 재검토", description = "② AI 재검토 → 새 DRAFT. LLM 호출로 수십 초 걸릴 수 있다")
    @PostMapping("/profiles/{profileId}/recheck")
    @ResponseStatus(HttpStatus.CREATED)
    public ProfileResponse recheck(@PathVariable Long profileId) {
        return revisionService.recheck(profileId);
    }

    /** ③ 강사 의견 반영 → 새 DRAFT. LLM 호출로 수십 초 걸릴 수 있다 */
    @Operation(summary = "강사 의견 반영", description = "③ 강사 의견 반영 → 새 DRAFT. LLM 호출로 수십 초 걸릴 수 있다")
    @PostMapping("/profiles/{profileId}/feedback")
    @ResponseStatus(HttpStatus.CREATED)
    public ProfileResponse feedback(@PathVariable Long profileId, @Valid @RequestBody FeedbackRequest request) {
        return revisionService.applyFeedback(profileId, request);
    }

    /** ④ 직접 수정 (규칙, 지문당 유형 구성) → 새 DRAFT */
    @Operation(summary = "직접 수정", description = "④ 직접 수정 (규칙, 지문당 유형 구성) → 새 DRAFT")
    @PatchMapping("/profiles/{profileId}")
    @ResponseStatus(HttpStatus.CREATED)
    public ProfileResponse edit(@PathVariable Long profileId, @Valid @RequestBody ManualEditRequest request) {
        return revisionService.manualEdit(profileId, request);
    }
}
