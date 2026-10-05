package com.smwu.backend.academy.service;

import com.smwu.backend.academy.domain.Academy;
import com.smwu.backend.academy.domain.AcademyPlan;
import com.smwu.backend.academy.domain.InviteCode;
import com.smwu.backend.academy.dto.AcademyDtos.AcademyResponse;
import com.smwu.backend.academy.dto.AcademyDtos.InviteResponse;
import com.smwu.backend.academy.dto.AcademyDtos.MemberResponse;
import com.smwu.backend.academy.repository.AcademyRepository;
import com.smwu.backend.academy.repository.InviteCodeRepository;
import com.smwu.backend.auth.dto.UserResponse;
import com.smwu.backend.auth.web.CurrentUserContext;
import com.smwu.backend.common.exception.BusinessException;
import com.smwu.backend.common.exception.ErrorCode;
import com.smwu.backend.user.domain.User;
import com.smwu.backend.user.domain.UserRole;
import com.smwu.backend.user.repository.UserRepository;
import com.smwu.backend.workspace.service.CurrentAcademy;
import lombok.RequiredArgsConstructor;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 학원 만들기, 초대 코드로 합류, 강사 내보내기, 요금제 강사 수 제한 (설계서 3.2~3.5).
 * 원장 전용 기능은 {@link CurrentAcademy#requireOwner()}로 확인한다 (강사면 403 OWNER_ONLY).
 */
@Service
@RequiredArgsConstructor
public class AcademyService {

    /** 헷갈리는 문자(0, O, 1, I) 제외 */
    static final String CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    static final int CODE_LENGTH = 6;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final AcademyRepository academyRepository;
    private final InviteCodeRepository inviteCodeRepository;
    private final UserRepository userRepository;
    private final CurrentAcademy currentAcademy;

    /** 소속 없는 사용자만. 만든 사람이 원장(OWNER)이 된다 */
    @Transactional
    public UserResponse create(String name) {
        User user = loadCurrentUser();
        if (user.getAcademyId() != null) {
            throw new BusinessException(ErrorCode.ALREADY_IN_ACADEMY);
        }
        Academy academy = academyRepository.save(new Academy(name.strip(), AcademyPlan.BASIC));
        user.joinAcademy(academy.getId(), UserRole.OWNER);
        return UserResponse.of(user);
    }

    /** 초대 코드로 합류 → 강사(TEACHER). 만료·사용됨·취소된 코드는 409 INVITE_CODE_INVALID */
    @Transactional
    public UserResponse join(String rawCode) {
        User user = loadCurrentUser();
        if (user.getAcademyId() != null) {
            throw new BusinessException(ErrorCode.ALREADY_IN_ACADEMY);
        }
        LocalDateTime now = LocalDateTime.now();
        InviteCode invite = inviteCodeRepository.findByCode(rawCode.strip().toUpperCase(Locale.ROOT))
                .filter(c -> c.status(now) == InviteCode.Status.ACTIVE)
                .orElseThrow(() -> new BusinessException(ErrorCode.INVITE_CODE_INVALID));
        Academy academy = getAcademy(invite.getAcademyId());
        requireTeacherSeat(academy);
        invite.use(user.getId(), now);
        user.joinAcademy(academy.getId(), UserRole.TEACHER);
        try {
            inviteCodeRepository.saveAndFlush(invite);
        } catch (ObjectOptimisticLockingFailureException e) {
            // 같은 코드를 다른 사람이 동시에 사용
            throw new BusinessException(ErrorCode.INVITE_CODE_INVALID);
        }
        return UserResponse.of(user);
    }

    @Transactional(readOnly = true)
    public AcademyResponse get() {
        Academy academy = getAcademy(currentAcademy.academyId());
        return AcademyResponse.of(academy, userRepository.countByAcademyIdAndRole(academy.getId(), UserRole.TEACHER));
    }

    @Transactional
    public AcademyResponse rename(String name) {
        currentAcademy.requireOwner();
        Academy academy = getAcademy(currentAcademy.academyId());
        academy.rename(name.strip());
        return AcademyResponse.of(academy, userRepository.countByAcademyIdAndRole(academy.getId(), UserRole.TEACHER));
    }

    /** 원장이 먼저, 그다음 들어온 순서 */
    @Transactional(readOnly = true)
    public List<MemberResponse> members() {
        return userRepository.findByAcademyIdOrderByIdAsc(currentAcademy.academyId()).stream()
                .sorted((a, b) -> Boolean.compare(b.isOwner(), a.isOwner()))
                .map(MemberResponse::of)
                .toList();
    }

    /** 강사 내보내기: 계정은 남고 소속·역할만 비운다. 작성한 기출·프로필·시험지는 학원에 남는다 */
    @Transactional
    public void removeMember(Long userId) {
        currentAcademy.requireOwner();
        Long academyId = currentAcademy.academyId();
        if (userId.equals(currentAcademy.userId())) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "원장은 자신을 내보낼 수 없습니다.");
        }
        User member = userRepository.findById(userId)
                .filter(u -> academyId.equals(u.getAcademyId()))
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        member.leaveAcademy();
    }

    /** 강사 자리가 남아 있을 때만 발급. 7일 유효, 1회용 */
    @Transactional
    public InviteResponse issueInvite() {
        currentAcademy.requireOwner();
        Academy academy = getAcademy(currentAcademy.academyId());
        requireTeacherSeat(academy);
        String code;
        do {
            code = randomCode();
        } while (inviteCodeRepository.existsByCode(code));
        LocalDateTime now = LocalDateTime.now();
        InviteCode invite = inviteCodeRepository.save(new InviteCode(academy.getId(), code, currentAcademy.userId(), now));
        return InviteResponse.of(invite, now, null);
    }

    @Transactional(readOnly = true)
    public List<InviteResponse> invites() {
        currentAcademy.requireOwner();
        List<InviteCode> codes = inviteCodeRepository.findByAcademyIdOrderByIdDesc(currentAcademy.academyId());
        Map<Long, String> names = userRepository.findAllById(codes.stream().map(InviteCode::getUsedBy)
                        .filter(Objects::nonNull).toList()).stream()
                .collect(Collectors.toMap(User::getId, User::getName));
        LocalDateTime now = LocalDateTime.now();
        return codes.stream().map(c -> InviteResponse.of(c, now, c.getUsedBy() == null ? null : names.get(c.getUsedBy()))).toList();
    }

    /** 사용 전 코드만 취소 (이미 쓴 코드는 409) */
    @Transactional
    public void cancelInvite(Long inviteId) {
        currentAcademy.requireOwner();
        InviteCode invite = inviteCodeRepository.findById(inviteId)
                .filter(c -> c.getAcademyId().equals(currentAcademy.academyId()))
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        if (invite.getUsedBy() != null) {
            throw new BusinessException(ErrorCode.INVITE_CODE_INVALID, "이미 사용된 초대 코드는 취소할 수 없습니다.");
        }
        invite.cancel();
    }

    private void requireTeacherSeat(Academy academy) {
        long teachers = userRepository.countByAcademyIdAndRole(academy.getId(), UserRole.TEACHER);
        if (teachers >= academy.getPlan().maxTeachers()) {
            throw new BusinessException(ErrorCode.PLAN_LIMIT_EXCEEDED,
                    "요금제의 최대 강사 수(" + academy.getPlan().maxTeachers() + "명)를 초과했습니다.");
        }
    }

    private Academy getAcademy(Long academyId) {
        return academyRepository.findById(academyId).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
    }

    /** 인터셉터가 담은 사용자는 다른 영속성 컨텍스트에서 읽은 것이라 이 트랜잭션에서 다시 읽는다 */
    private User loadCurrentUser() {
        return userRepository.findById(CurrentUserContext.get().getId())
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHENTICATED));
    }

    static String randomCode() {
        StringBuilder sb = new StringBuilder(CODE_LENGTH);
        for (int i = 0; i < CODE_LENGTH; i++) {
            sb.append(CODE_ALPHABET.charAt(RANDOM.nextInt(CODE_ALPHABET.length())));
        }
        return sb.toString();
    }
}
