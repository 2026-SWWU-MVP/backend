package com.smwu.backend.user.domain;

import com.smwu.backend.common.domain.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 계정. 학원에 소속되기 전에는 role, academyId가 null */
@Entity
@Table(name = "users", uniqueConstraints = @UniqueConstraint(name = "uk_users_login_id", columnNames = "loginId"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class User extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 20)
    private String loginId;

    /** BCrypt 해시 (평문 저장 금지) */
    @Column(nullable = false, length = 100)
    private String passwordHash;

    @Column(nullable = false, length = 30)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private UserRole role;

    private Long academyId;

    public User(String loginId, String passwordHash, String name) {
        this.loginId = loginId;
        this.passwordHash = passwordHash;
        this.name = name;
    }

    /** 학원 만들기(OWNER) 또는 초대 코드로 합류(TEACHER) */
    public void joinAcademy(Long academyId, UserRole role) {
        this.academyId = academyId;
        this.role = role;
    }

    /** 원장이 내보내면 소속과 역할이 비워진다. 계정은 남는다 */
    public void leaveAcademy() {
        this.academyId = null;
        this.role = null;
    }

    public boolean isOwner() {
        return role == UserRole.OWNER;
    }
}
