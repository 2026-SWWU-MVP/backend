package com.smwu.backend.user.repository;

import com.smwu.backend.user.domain.User;
import com.smwu.backend.user.domain.UserRole;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {

    boolean existsByLoginId(String loginId);

    Optional<User> findByLoginId(String loginId);

    List<User> findByAcademyIdOrderByIdAsc(Long academyId);

    long countByAcademyIdAndRole(Long academyId, UserRole role);
}
