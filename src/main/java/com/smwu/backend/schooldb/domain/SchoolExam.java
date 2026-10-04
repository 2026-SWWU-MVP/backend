package com.smwu.backend.schooldb.domain;

import com.smwu.backend.common.domain.BaseTimeEntity;
import com.smwu.backend.pastexam.domain.ExamType;
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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 학교 DB의 기출 회차 (학교 + 학년 + 연도 + 학기 + 시험 종류). 여러 학원이 같은 회차를 올려도 하나로 센다.
 * 원문은 없고 대표 기여의 통계와 기여 학원 수만 있다.
 */
@Entity
@Table(name = "school_exam", uniqueConstraints = @UniqueConstraint(name = "uk_school_exam_round",
        columnNames = {"schoolId", "grade", "examYear", "semester", "examType"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SchoolExam extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long schoolId;

    private int grade;

    private int examYear;

    private int semester;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ExamType examType;

    /** 대표 기여(점검 이슈가 가장 적은 추출 결과)의 통계 */
    @JdbcTypeCode(SqlTypes.JSON)
    private ExamRoundStats stats;

    private Long representativeContributionId;

    /** 이 회차를 올린 학원 수 */
    private int contributorCount;

    public SchoolExam(Long schoolId, int grade, int examYear, int semester, ExamType examType) {
        this.schoolId = schoolId;
        this.grade = grade;
        this.examYear = examYear;
        this.semester = semester;
        this.examType = examType;
    }

    public void refresh(ExamContribution representative, int contributorCount) {
        this.stats = representative.getStats();
        this.representativeContributionId = representative.getId();
        this.contributorCount = contributorCount;
    }

    /** 정렬용: 2025년 2학기 기말 > 2025년 2학기 중간 > 2025년 1학기 기말 ... */
    public int order() {
        return examYear * 100 + semester * 10 + (examType == ExamType.FINAL ? 1 : 0);
    }
}
