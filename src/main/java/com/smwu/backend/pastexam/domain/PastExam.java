package com.smwu.backend.pastexam.domain;

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
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/** 업로드한 기출 시험지 PDF와 추출 상태 */
@Entity
@Table(name = "past_exam", indexes = @Index(name = "idx_past_exam_workspace", columnList = "workspaceId"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PastExam extends BaseTimeEntity {

    private static final int FAILURE_REASON_MAX = 1000;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long workspaceId;

    @Column(nullable = false)
    private Integer examYear;

    /** 1학기 / 2학기 */
    @Column(nullable = false)
    private Integer semester;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ExamType examType;

    @Column(nullable = false)
    private String originalFilename;

    /** FileStorage 기준 상대 경로 */
    @Column(nullable = false)
    private String filePath;

    private long fileSize;

    private int pageCount;

    /** 텍스트 레이어가 있는 PDF인지 (false면 스캔본) */
    private boolean textLayer;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PastExamStatus status;

    @Column(length = FAILURE_REASON_MAX)
    private String failureReason;

    /** 모델이 남긴 경고 (판독 불가, 애매한 유형 등) */
    @JdbcTypeCode(SqlTypes.JSON)
    private List<String> warnings = new ArrayList<>();

    private String llmModel;

    private Integer inputTokens;

    private Integer outputTokens;

    private Long extractionMillis;

    private LocalDateTime extractedAt;

    /** 모델 응답 원문. 디버깅용이며 화면 데이터로 쓰지 않는다 */
    @JdbcTypeCode(SqlTypes.LONG32VARCHAR)
    private String rawResponse;

    /** 올린 사용자 (로그인 기능 이전 데이터는 null) */
    private Long createdBy;

    @Builder
    private PastExam(Long workspaceId, Integer examYear, Integer semester, ExamType examType, String originalFilename,
                     String filePath, long fileSize, int pageCount, boolean textLayer, Long createdBy) {
        this.workspaceId = workspaceId;
        this.examYear = examYear;
        this.semester = semester;
        this.examType = examType;
        this.originalFilename = originalFilename;
        this.filePath = filePath;
        this.fileSize = fileSize;
        this.pageCount = pageCount;
        this.textLayer = textLayer;
        this.createdBy = createdBy;
        this.status = PastExamStatus.UPLOADED;
    }

    /** 추출 시작. 이미 추출 중이면 거부한다. 다시 추출하면 기존 결과는 새 결과로 바뀐다 */
    public void startExtraction() {
        if (status == PastExamStatus.EXTRACTING) {
            throw new BusinessException(ErrorCode.EXTRACTION_IN_PROGRESS);
        }
        status = PastExamStatus.EXTRACTING;
        failureReason = null;
    }

    public void completeExtraction(List<String> warnings, String llmModel, int inputTokens, int outputTokens,
                                   long extractionMillis, String rawResponse) {
        this.status = PastExamStatus.EXTRACTED;
        this.warnings = new ArrayList<>(warnings);
        this.llmModel = llmModel;
        this.inputTokens = inputTokens;
        this.outputTokens = outputTokens;
        this.extractionMillis = extractionMillis;
        this.rawResponse = rawResponse;
        this.extractedAt = LocalDateTime.now();
        this.failureReason = null;
    }

    public void failExtraction(String reason) {
        this.status = PastExamStatus.FAILED;
        this.failureReason = reason == null || reason.length() <= FAILURE_REASON_MAX
                ? reason : reason.substring(0, FAILURE_REASON_MAX);
    }

    /** 추출 결과(문항·지문)를 사람이 수정할 수 있는 상태인지 확인한다 */
    public void requireExtracted() {
        if (status == PastExamStatus.EXTRACTING) {
            throw new BusinessException(ErrorCode.EXTRACTION_IN_PROGRESS);
        }
        if (status != PastExamStatus.EXTRACTED) {
            throw new BusinessException(ErrorCode.EXTRACTION_NOT_COMPLETED);
        }
    }
}
