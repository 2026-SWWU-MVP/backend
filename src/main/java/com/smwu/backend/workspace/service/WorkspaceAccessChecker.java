package com.smwu.backend.workspace.service;

import com.smwu.backend.auth.web.CurrentUserContext;
import com.smwu.backend.common.exception.BusinessException;
import com.smwu.backend.common.exception.ErrorCode;
import com.smwu.backend.workspace.domain.Workspace;
import com.smwu.backend.workspace.repository.WorkspaceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 학원 간 데이터 격리 (설계서 3.7). 워크스페이스 하위 리소스(기출, 프로필, 자료, 지문, 문제, 생성 작업, 시험지)를
 * 조회·변경하는 서비스는 리소스의 workspaceId로 {@link #check}를 부른다.
 * <ul>
 *   <li>academyId는 요청에서 받지 않고 항상 현재 사용자(X-User-Id)에서 꺼낸다</li>
 *   <li>다른 학원의 워크스페이스이거나 없는 워크스페이스면 404 (존재 여부도 알리지 않음)</li>
 *   <li>학원 소속 전이면 403 NO_ACADEMY</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class WorkspaceAccessChecker {

    private final WorkspaceRepository workspaceRepository;
    private final CurrentAcademy currentAcademy;

    /**
     * 로그인 사용자가 없는 내부 호출(이미 확인을 거친 요청이 시작한 비동기 작업, 서비스 테스트 등)은 확인하지 않는다.
     * /api/** 요청은 인터셉터가 사용자를 보장하므로 외부 요청이 이 조건으로 빠져나갈 수 없다.
     */
    public void check(Long workspaceId) {
        if (CurrentUserContext.find().isEmpty()) {
            return;
        }
        get(workspaceId);
    }

    /** 내 학원 워크스페이스. 요청 안에서만 부른다 (사용자가 없으면 401) */
    public Workspace get(Long workspaceId) {
        Long academyId = currentAcademy.academyId();
        if (workspaceId == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
        return workspaceRepository.findById(workspaceId)
                .filter(w -> w.getAcademyId().equals(academyId))
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
    }
}
