package com.smwu.backend.schooldb.domain;

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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.ArrayList;
import java.util.List;

/**
 * 학교 경향 요약(LLM) 캐시. 학교 + 학년마다 하나.
 * basisKey는 요약에 넣은 통계 입력의 해시라, 회차가 추가·삭제되거나 통계가 바뀌었을 때만 다시 만든다.
 */
@Entity
@Table(name = "school_trend_summary", uniqueConstraints = @UniqueConstraint(name = "uk_trend_summary_school_grade",
        columnNames = {"schoolId", "grade"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SchoolTrendSummary extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long schoolId;

    private int grade;

    @Column(nullable = false, length = 64)
    private String basisKey;

    private int examCount;

    @Column(length = 300)
    private String headline;

    @JdbcTypeCode(SqlTypes.JSON)
    private List<String> points = new ArrayList<>();

    @JdbcTypeCode(SqlTypes.JSON)
    private List<String> prepTips = new ArrayList<>();

    private String llmModel;

    public SchoolTrendSummary(Long schoolId, int grade) {
        this.schoolId = schoolId;
        this.grade = grade;
    }

    public void update(String basisKey, int examCount, String headline, List<String> points, List<String> prepTips, String llmModel) {
        this.basisKey = basisKey;
        this.examCount = examCount;
        this.headline = headline;
        this.points = new ArrayList<>(points);
        this.prepTips = new ArrayList<>(prepTips);
        this.llmModel = llmModel;
    }
}
