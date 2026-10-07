package com.smwu.backend.worksheet.repository;

import com.smwu.backend.worksheet.domain.Worksheet;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface WorksheetRepository extends JpaRepository<Worksheet, Long> {

    List<Worksheet> findByWorkspaceIdOrderByIdDesc(Long workspaceId);

    boolean existsByWorkspaceId(Long workspaceId);
}
