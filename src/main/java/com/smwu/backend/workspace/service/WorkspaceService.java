package com.smwu.backend.workspace.service;

import com.smwu.backend.common.exception.BusinessException;
import com.smwu.backend.common.exception.ErrorCode;
import com.smwu.backend.material.repository.MaterialRepository;
import com.smwu.backend.pastexam.repository.PastExamRepository;
import com.smwu.backend.profile.repository.SchoolProfileRepository;
import com.smwu.backend.workspace.domain.School;
import com.smwu.backend.workspace.domain.Workspace;
import com.smwu.backend.workspace.dto.WorkspaceRequest;
import com.smwu.backend.workspace.dto.WorkspaceResponse;
import com.smwu.backend.workspace.repository.SchoolRepository;
import com.smwu.backend.workspace.repository.WorkspaceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 워크스페이스(학교 + 학년). 학원 안의 모든 강사가 같은 목록을 본다 */
@Service
@RequiredArgsConstructor
public class WorkspaceService {

    private final WorkspaceRepository workspaceRepository;
    private final SchoolRepository schoolRepository;
    private final SchoolService schoolService;
    private final CurrentAcademy currentAcademy;
    private final PastExamRepository pastExamRepository;
    private final MaterialRepository materialRepository;
    private final SchoolProfileRepository profileRepository;

    @Transactional
    public WorkspaceResponse create(WorkspaceRequest request) {
        Long academyId = currentAcademy.academyId();
        School school = schoolService.getSchool(request.schoolId());
        if (workspaceRepository.existsByAcademyIdAndSchoolIdAndGrade(academyId, school.getId(), request.grade())) {
            throw new BusinessException(ErrorCode.WORKSPACE_DUPLICATED);
        }
        try {
            Workspace saved = workspaceRepository.saveAndFlush(
                    new Workspace(academyId, school.getId(), request.grade(), currentAcademy.userId()));
            return WorkspaceResponse.of(saved, school);
        } catch (DataIntegrityViolationException e) {
            // 같은 요청이 동시에 두 번 들어온 경우
            throw new BusinessException(ErrorCode.WORKSPACE_DUPLICATED);
        }
    }

    @Transactional(readOnly = true)
    public List<WorkspaceResponse> list() {
        List<Workspace> workspaces = workspaceRepository.findByAcademyIdOrderByIdAsc(currentAcademy.academyId());
        Map<Long, School> schools = schoolRepository.findAllById(workspaces.stream().map(Workspace::getSchoolId).distinct().toList())
                .stream().collect(Collectors.toMap(School::getId, Function.identity()));
        return workspaces.stream().map(w -> WorkspaceResponse.of(w, schools.get(w.getSchoolId()))).toList();
    }

    @Transactional(readOnly = true)
    public WorkspaceResponse get(Long workspaceId) {
        Workspace workspace = getWorkspace(workspaceId);
        return WorkspaceResponse.of(workspace, schoolService.getSchool(workspace.getSchoolId()));
    }

    /**
     * 비어 있는 워크스페이스만 지운다 (잘못 만든 경우). 기출·시험범위·프로필이 있으면 409. 원장만 (403 OWNER_ONLY)
     */
    @Transactional
    public void delete(Long workspaceId) {
        currentAcademy.requireOwner();
        Workspace workspace = getWorkspace(workspaceId);
        if (pastExamRepository.existsByWorkspaceId(workspaceId) || materialRepository.existsByWorkspaceId(workspaceId)
                || profileRepository.existsByWorkspaceId(workspaceId)) {
            throw new BusinessException(ErrorCode.WORKSPACE_NOT_EMPTY);
        }
        workspaceRepository.delete(workspace);
    }

    /** 다른 학원의 워크스페이스는 존재 자체를 알리지 않도록 404 */
    public Workspace getWorkspace(Long workspaceId) {
        return workspaceRepository.findById(workspaceId)
                .filter(w -> w.getAcademyId().equals(currentAcademy.academyId()))
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
    }
}
