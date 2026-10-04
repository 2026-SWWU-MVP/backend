package com.smwu.backend.workspace.domain;

import com.smwu.backend.common.domain.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 학원이 맡은 학교 + 학년. 기출, 프로필, 지문, 문제, 시험지가 모두 여기에 속한다 */
@Entity
@Table(name = "workspace", uniqueConstraints = @UniqueConstraint(name = "uk_workspace_academy_school_grade",
        columnNames = {"academyId", "schoolId", "grade"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Workspace extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long academyId;

    @Column(nullable = false)
    private Long schoolId;

    /** 1~3 */
    private int grade;

    private Long createdBy;

    public Workspace(Long academyId, Long schoolId, int grade, Long createdBy) {
        this.academyId = academyId;
        this.schoolId = schoolId;
        this.grade = grade;
        this.createdBy = createdBy;
    }
}
