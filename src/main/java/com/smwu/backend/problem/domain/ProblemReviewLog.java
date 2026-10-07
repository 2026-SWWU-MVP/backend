package com.smwu.backend.problem.domain;

import com.smwu.backend.common.domain.BaseTimeEntity;
import com.smwu.backend.pastexam.extraction.QuestionType;
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

/**
 * 강사 검수 기록 (#42). 채택·폐기·수정할 때마다 남기고, 같은 워크스페이스·유형의 다음 생성 프롬프트에 반영한다.
 * 학원 안에서만 쓴다 (학교 DB에 공유하지 않음).
 */
@Entity
@Table(name = "problem_review_log", indexes = @Index(name = "idx_review_log_workspace_type", columnList = "workspaceId, type"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProblemReviewLog extends BaseTimeEntity {

    public enum Action {
        ACCEPTED, REJECTED, EDITED
    }

    /** @param field stem / conditions / body / choices / answerText / explanation */
    public record FieldChange(String field, String before, String after) {
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long workspaceId;

    @Column(nullable = false)
    private Long problemId;

    private Long generationJobId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private QuestionType type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Action action;

    /** 폐기 사유 (강사 입력, 없으면 null) */
    @Column(length = 300)
    private String reason;

    @JdbcTypeCode(SqlTypes.JSON)
    private List<FieldChange> changes = new ArrayList<>();

    /** 기록할 때의 문항 요약 (정답, 없으면 발문). 프롬프트에 "어떤 문항이었는지" 보여주는 용도 */
    @Column(length = 300)
    private String problemSummary;

    private Long createdBy;

    public ProblemReviewLog(Problem problem, Action action, String reason, List<FieldChange> changes, Long createdBy) {
        this.workspaceId = problem.getWorkspaceId();
        this.problemId = problem.getId();
        this.generationJobId = problem.getGenerationJobId();
        this.type = problem.getType();
        this.action = action;
        this.reason = reason;
        this.changes = new ArrayList<>(changes);
        this.createdBy = createdBy;
        String summary = problem.getAnswerText() != null ? problem.getAnswerText() : problem.getStem();
        this.problemSummary = summary == null ? null : summary.length() > 300 ? summary.substring(0, 297) + "..." : summary;
    }
}
