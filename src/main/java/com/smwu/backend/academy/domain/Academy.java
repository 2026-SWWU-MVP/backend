package com.smwu.backend.academy.domain;

import com.smwu.backend.common.domain.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 학원 (계약 단위). 로고는 모든 시험지 머리글에 들어간다 (설계서 8) */
@Entity
@Table(name = "academy")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Academy extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 50)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AcademyPlan plan;

    /** 저장 루트 기준 상대 경로 (PNG). 없으면 null → PDF 머리글에 학원명 텍스트 */
    @Column(length = 300)
    private String logoPath;

    /** 머리글 배치 계산용 픽셀 크기 */
    private Integer logoWidth;

    private Integer logoHeight;

    public Academy(String name, AcademyPlan plan) {
        this.name = name;
        this.plan = plan;
    }

    public void rename(String name) {
        this.name = name;
    }

    public void changeLogo(String path, int width, int height) {
        this.logoPath = path;
        this.logoWidth = width;
        this.logoHeight = height;
    }

    public void removeLogo() {
        this.logoPath = null;
        this.logoWidth = null;
        this.logoHeight = null;
    }

    public boolean hasLogo() {
        return logoPath != null;
    }
}
