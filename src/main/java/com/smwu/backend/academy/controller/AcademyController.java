package com.smwu.backend.academy.controller;

import com.smwu.backend.academy.dto.AcademyDtos.AcademyRequest;
import com.smwu.backend.academy.dto.AcademyDtos.AcademyResponse;
import com.smwu.backend.academy.dto.AcademyDtos.InviteResponse;
import com.smwu.backend.academy.dto.AcademyDtos.JoinRequest;
import com.smwu.backend.academy.dto.AcademyDtos.MemberResponse;
import com.smwu.backend.academy.service.AcademyService;
import com.smwu.backend.academy.service.LogoService;
import com.smwu.backend.auth.dto.UserResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 학원과 강사 (설계서 3.2~3.5). 원장 전용(🔒)을 강사가 부르면 403 OWNER_ONLY.
 * 학원 만들기·합류 응답은 갱신된 내 정보(role, academyId)다.
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class AcademyController {

    private final AcademyService academyService;
    private final LogoService logoService;

    /** 학원 만들기 → 요청자가 원장. 이미 소속이 있으면 409 ALREADY_IN_ACADEMY */
    @PostMapping("/academies")
    @ResponseStatus(HttpStatus.CREATED)
    public UserResponse create(@Valid @RequestBody AcademyRequest request) {
        return academyService.create(request.name());
    }

    /** 초대 코드로 합류 → 강사. 만료·사용됨·취소 코드 409 INVITE_CODE_INVALID, 강사 수 초과 409 PLAN_LIMIT_EXCEEDED */
    @PostMapping("/academies/join")
    public UserResponse join(@Valid @RequestBody JoinRequest request) {
        return academyService.join(request.code());
    }

    @GetMapping("/academy")
    public AcademyResponse get() {
        return academyService.get();
    }

    /** 🔒 학원 이름 수정 */
    @PatchMapping("/academy")
    public AcademyResponse rename(@Valid @RequestBody AcademyRequest request) {
        return academyService.rename(request.name());
    }

    /** 🔒 로고 업로드 (PNG/JPG, 2MB 이하, 가로 600px 초과 시 축소). 모든 시험지 머리글에 들어간다 */
    @PostMapping(value = "/academy/logo", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public AcademyResponse uploadLogo(@RequestPart("file") MultipartFile file) {
        return logoService.upload(file);
    }

    /** 로고 미리보기 (PNG). 없으면 404 */
    @GetMapping(value = "/academy/logo", produces = MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<byte[]> logo() {
        return ResponseEntity.ok().cacheControl(CacheControl.noCache()).body(logoService.currentLogo());
    }

    /** 🔒 로고 삭제 → 시험지 머리글에 학원명 텍스트 */
    @DeleteMapping("/academy/logo")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteLogo() {
        logoService.delete();
    }

    @GetMapping("/academy/members")
    public List<MemberResponse> members() {
        return academyService.members();
    }

    /** 🔒 강사 내보내기 (원장 자신은 불가) */
    @DeleteMapping("/academy/members/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeMember(@PathVariable Long userId) {
        academyService.removeMember(userId);
    }

    /** 🔒 초대 코드 발급 → { code, expiresAt } */
    @PostMapping("/academy/invites")
    @ResponseStatus(HttpStatus.CREATED)
    public InviteResponse issueInvite() {
        return academyService.issueInvite();
    }

    /** 🔒 발급한 코드 목록 (사용 여부 포함) */
    @GetMapping("/academy/invites")
    public List<InviteResponse> invites() {
        return academyService.invites();
    }

    /** 🔒 사용 전 코드 취소 */
    @DeleteMapping("/academy/invites/{inviteId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void cancelInvite(@PathVariable Long inviteId) {
        academyService.cancelInvite(inviteId);
    }
}
