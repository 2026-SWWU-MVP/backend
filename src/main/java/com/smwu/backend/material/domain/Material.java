package com.smwu.backend.material.domain;

import com.smwu.backend.common.domain.BaseTimeEntity;
import com.smwu.backend.common.exception.BusinessException;
import com.smwu.backend.common.exception.ErrorCode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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

import java.util.ArrayList;
import java.util.List;

/** 시험범위 자료 (교과서 2과, 3월 모의고사 등). 지문(Passage) 단위로 나눠 문제 생성에 쓴다 */
@Entity
@Table(name = "material", indexes = @Index(name = "idx_material_workspace", columnList = "workspaceId"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Material extends BaseTimeEntity {

    private static final int FAILURE_REASON_MAX = 1000;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long workspaceId;

    /** 예) 교과서 2과, 2025 고1 3월 모의고사 */
    @Column(nullable = false, length = 200)
    private String title;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private MaterialSourceType sourceType;

    /** PDF일 때 FileStorage 상대 경로 */
    private String filePath;

    private String originalFilename;

    private int pageCount;

    private boolean textLayer;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private MaterialStatus status;

    @Column(length = FAILURE_REASON_MAX)
    private String failureReason;

    /** 지문을 나누면서 남긴 경고 (판독 불가, 문제용으로 변형된 지문 등) */
    @JdbcTypeCode(SqlTypes.JSON)
    private List<String> warnings = new ArrayList<>();

    private String llmModel;

    /** 올린 사용자 (로그인 기능 이전 데이터는 null) */
    private Long createdBy;

    public static Material pdf(Long workspaceId, String title, String filePath, String originalFilename, int pageCount,
                               boolean textLayer, Long createdBy) {
        Material m = new Material();
        m.createdBy = createdBy;
        m.workspaceId = workspaceId;
        m.title = title;
        m.sourceType = MaterialSourceType.PDF;
        m.filePath = filePath;
        m.originalFilename = originalFilename;
        m.pageCount = pageCount;
        m.textLayer = textLayer;
        m.status = MaterialStatus.UPLOADED;
        return m;
    }

    /** 붙여넣은 텍스트는 코드가 바로 나누므로 처음부터 SPLIT */
    public static Material text(Long workspaceId, String title, Long createdBy) {
        Material m = new Material();
        m.createdBy = createdBy;
        m.workspaceId = workspaceId;
        m.title = title;
        m.sourceType = MaterialSourceType.TEXT;
        m.status = MaterialStatus.SPLIT;
        return m;
    }

    public void startSplit() {
        if (sourceType != MaterialSourceType.PDF) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "PDF 자료만 지문을 다시 나눌 수 있습니다.");
        }
        if (status == MaterialStatus.SPLITTING) {
            throw new BusinessException(ErrorCode.SPLIT_IN_PROGRESS);
        }
        status = MaterialStatus.SPLITTING;
        failureReason = null;
    }

    public void completeSplit(List<String> warnings, String llmModel) {
        this.status = MaterialStatus.SPLIT;
        this.warnings = new ArrayList<>(warnings);
        this.llmModel = llmModel;
        this.failureReason = null;
    }

    public void failSplit(String reason) {
        this.status = MaterialStatus.FAILED;
        this.failureReason = reason == null || reason.length() <= FAILURE_REASON_MAX ? reason : reason.substring(0, FAILURE_REASON_MAX);
    }

    /** 지문을 추가·수정·삭제할 수 있는 상태인지 */
    public void requireNotSplitting() {
        if (status == MaterialStatus.SPLITTING) {
            throw new BusinessException(ErrorCode.SPLIT_IN_PROGRESS);
        }
    }

    public void rename(String title) {
        this.title = title;
    }
}
