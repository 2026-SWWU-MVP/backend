package com.smwu.backend.profile.repository;

import com.smwu.backend.profile.domain.SchoolProfile;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SchoolProfileRepository extends JpaRepository<SchoolProfile, Long> {

    List<SchoolProfile> findByWorkspaceIdOrderByVersionDesc(Long workspaceId);

    Optional<SchoolProfile> findTopByWorkspaceIdOrderByVersionDesc(Long workspaceId);
}
