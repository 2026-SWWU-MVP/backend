package com.smwu.backend.academy.domain;

import com.smwu.backend.common.domain.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 강사 초대 코드 (설계서 3.3). 6자리, 7일 유효, 1회용. 원장이 카톡 등으로 직접 전달한다 (이메일 발송 없음).
 * 같은 코드를 두 사람이 동시에 쓰지 못하게 낙관적 락(version)을 둔다.
 */
@Entity
@Table(name = "invite_code", uniqueConstraints = @UniqueConstraint(name = "uk_invite_code", columnNames = "code"),
        indexes = @Index(name = "idx_invite_academy", columnList = "academyId"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class InviteCode extends BaseTimeEntity {

    public static final int VALID_DAYS = 7;

    public enum Status {
        ACTIVE, USED, EXPIRED, CANCELED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long academyId;

    @Column(nullable = false, length = 6)
    private String code;

    private Long createdBy;

    @Column(nullable = false)
    private LocalDateTime expiresAt;

    private Long usedBy;

    private LocalDateTime usedAt;

    private boolean canceled;

    @Version
    private long version;

    public InviteCode(Long academyId, String code, Long createdBy, LocalDateTime now) {
        this.academyId = academyId;
        this.code = code;
        this.createdBy = createdBy;
        this.expiresAt = now.plusDays(VALID_DAYS);
    }

    public Status status(LocalDateTime now) {
        if (usedBy != null) {
            return Status.USED;
        }
        if (canceled) {
            return Status.CANCELED;
        }
        return now.isAfter(expiresAt) ? Status.EXPIRED : Status.ACTIVE;
    }

    public void use(Long userId, LocalDateTime now) {
        this.usedBy = userId;
        this.usedAt = now;
    }

    public void cancel() {
        this.canceled = true;
    }
}
