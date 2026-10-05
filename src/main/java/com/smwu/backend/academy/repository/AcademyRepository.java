package com.smwu.backend.academy.repository;

import com.smwu.backend.academy.domain.Academy;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AcademyRepository extends JpaRepository<Academy, Long> {
}
