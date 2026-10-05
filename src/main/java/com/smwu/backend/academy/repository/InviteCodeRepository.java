package com.smwu.backend.academy.repository;

import com.smwu.backend.academy.domain.InviteCode;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface InviteCodeRepository extends JpaRepository<InviteCode, Long> {

    Optional<InviteCode> findByCode(String code);

    boolean existsByCode(String code);

    List<InviteCode> findByAcademyIdOrderByIdDesc(Long academyId);
}
