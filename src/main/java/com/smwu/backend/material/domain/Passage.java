package com.smwu.backend.material.domain;

import com.smwu.backend.common.domain.BaseTimeEntity;
import com.smwu.backend.problem.type.PassageSource;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** 시험범위 지문. 문제는 지문 단위로 만든다 (출력예시: 지문 하나 + 서답형 2~3문항) */
@Entity
@Table(name = "passage", indexes = @Index(name = "idx_passage_material", columnList = "materialId"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Passage extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long materialId;

    /** 접근 확인용 (자료를 거치지 않고 지문만 조회할 때) */
    @Column(nullable = false)
    private Long workspaceId;

    private int orderNo;

    @Column(length = 300)
    private String title;

    /** 출처 표시 (예: 2025 고1 3월 모의고사 20번, Lesson 2 본문) */
    @Column(length = 200)
    private String sourceLabel;

    @JdbcTypeCode(SqlTypes.LONG32VARCHAR)
    @Column(nullable = false)
    private String content;

    private boolean edited;

    public Passage(Long materialId, Long workspaceId, int orderNo, String title, String sourceLabel, String content) {
        this.materialId = materialId;
        this.workspaceId = workspaceId;
        this.orderNo = orderNo;
        this.title = title;
        this.sourceLabel = sourceLabel;
        this.content = content;
    }

    /** null인 항목은 그대로, 빈 문자열이면 지운다 (본문은 비울 수 없음) */
    public void edit(String title, String sourceLabel, String content, Integer orderNo) {
        if (title != null) {
            this.title = title.isBlank() ? null : title.strip();
        }
        if (sourceLabel != null) {
            this.sourceLabel = sourceLabel.isBlank() ? null : sourceLabel.strip();
        }
        if (content != null) {
            this.content = content.strip();
        }
        if (orderNo != null) {
            this.orderNo = orderNo;
        }
        this.edited = true;
    }

    public PassageSource toSource() {
        return new PassageSource(id, title, content);
    }
}
