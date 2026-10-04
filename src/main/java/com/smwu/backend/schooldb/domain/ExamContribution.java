package com.smwu.backend.schooldb.domain;

import com.smwu.backend.common.domain.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.Comparator;

/**
 * 학원 기출 1개가 학교 DB 회차에 기여한 기록. 어느 학원인지는 학교 DB 조회 응답에 드러나지 않는다.
 */
@Entity
@Table(name = "exam_contribution",
        uniqueConstraints = @UniqueConstraint(name = "uk_contribution_past_exam", columnNames = "pastExamId"),
        indexes = @Index(name = "idx_contribution_school_exam", columnList = "schoolExamId"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ExamContribution extends BaseTimeEntity {

    /** 대표 기여: 점검 이슈가 적은 것 → 문항이 많은 것 → 먼저 올린 것 */
    public static final Comparator<ExamContribution> REPRESENTATIVE_ORDER = Comparator
            .comparingInt(ExamContribution::getIssueCount)
            .thenComparing(Comparator.comparingInt((ExamContribution c) -> c.getStats().totalQuestions()).reversed())
            .thenComparing(ExamContribution::getId);

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long schoolExamId;

    @Column(nullable = false)
    private Long pastExamId;

    @Column(nullable = false)
    private Long academyId;

    @JdbcTypeCode(SqlTypes.JSON)
    private ExamRoundStats stats;

    /** 추출 결과 점검 이슈 수 (적을수록 믿을 만함) */
    private int issueCount;

    public ExamContribution(Long schoolExamId, Long pastExamId, Long academyId) {
        this.schoolExamId = schoolExamId;
        this.pastExamId = pastExamId;
        this.academyId = academyId;
    }

    public void update(ExamRoundStats stats, int issueCount) {
        this.stats = stats;
        this.issueCount = issueCount;
    }
}
