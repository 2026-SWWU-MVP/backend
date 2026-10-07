package com.smwu.backend.worksheet.domain;

import com.smwu.backend.common.domain.BaseTimeEntity;
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

/** 시험지 설정 (1단 고정). 문항은 {@link WorksheetItem}, 학원 안의 모든 강사가 본다 */
@Entity
@Table(name = "worksheet", indexes = @Index(name = "idx_worksheet_workspace", columnList = "workspaceId"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Worksheet extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long workspaceId;

    @Column(nullable = false, length = 100)
    private String title;

    /** 머리글 문구 (예: 2026 1학기 중간고사 대비 · 2과). 없으면 null */
    @Column(length = 200)
    private String headerText;

    /** 학원 로고를 머리글에 넣을지 */
    private boolean showLogo;

    private Long createdBy;

    public Worksheet(Long workspaceId, String title, String headerText, boolean showLogo, Long createdBy) {
        this.workspaceId = workspaceId;
        this.title = title;
        this.headerText = headerText;
        this.showLogo = showLogo;
        this.createdBy = createdBy;
    }

    /** null은 그대로, headerText를 빈 문자열로 보내면 지운다 */
    public void update(String title, String headerText, Boolean showLogo) {
        if (title != null) {
            this.title = title;
        }
        if (headerText != null) {
            this.headerText = headerText.isBlank() ? null : headerText;
        }
        if (showLogo != null) {
            this.showLogo = showLogo;
        }
    }
}
