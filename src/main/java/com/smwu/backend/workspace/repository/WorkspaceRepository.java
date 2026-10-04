package com.smwu.backend.workspace.repository;

import com.smwu.backend.workspace.domain.Workspace;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface WorkspaceRepository extends JpaRepository<Workspace, Long> {

    List<Workspace> findByAcademyIdOrderByIdAsc(Long academyId);

    boolean existsByAcademyIdAndSchoolIdAndGrade(Long academyId, Long schoolId, int grade);
}
