package com.smwu.backend.problem.domain;

import com.smwu.backend.common.domain.BaseTimeEntity;
import com.smwu.backend.pastexam.extraction.QuestionType;
import com.smwu.backend.problem.type.AssembledProblem;
import com.smwu.backend.problem.type.PassageSource;
import com.smwu.backend.problem.type.ProblemAnswer;
import com.smwu.backend.problem.type.ProblemOptions;
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
 * 생성된 문항. 자동 검증 상태(validationStatus)와 강사 검수 상태(reviewStatus)를 따로 관리한다.
 * 규칙 검증에 실패한 문항도 저장해서 강사가 확인하거나 다시 생성할 수 있게 한다.
 */
@Entity
@Table(name = "problem", indexes = {
        @Index(name = "idx_problem_workspace", columnList = "workspaceId"),
        @Index(name = "idx_problem_job", columnList = "generationJobId")
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Problem extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long workspaceId;

    /** 생성에 쓴 확정 프로필 버전 */
    private Long profileId;

    /** 원문 시험범위 지문(Passage) ID. 지문이 삭제돼도 아래 사본이 남는다 */
    private Long passageId;

    /** 생성할 때 쓴 지문 제목 사본 */
    private String passageTitle;

    /** 생성할 때 쓴 지문 본문 사본. 지문이 나중에 수정돼도 이 문제를 만든 원문이 남고, 개별 재생성에 쓴다 */
    @JdbcTypeCode(SqlTypes.LONG32VARCHAR)
    private String passageText;

    /** 생성 작업 (#17). 단건 생성이면 null */
    private Long generationJobId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private QuestionType type;

    @JdbcTypeCode(SqlTypes.JSON)
    private ProblemOptions options;

    /** 발문. 조립 전에 실패했으면 null */
    @Column(length = 1000)
    private String stem;

    @JdbcTypeCode(SqlTypes.JSON)
    private List<String> conditions = new ArrayList<>();

    @JdbcTypeCode(SqlTypes.LONG32VARCHAR)
    private String body;

    @JdbcTypeCode(SqlTypes.JSON)
    private List<String> choices = new ArrayList<>();

    @JdbcTypeCode(SqlTypes.JSON)
    private ProblemAnswer answer;

    /** 정답지에 찍을 문자열 */
    @Column(length = 2000)
    private String answerText;

    @JdbcTypeCode(SqlTypes.LONG32VARCHAR)
    private String explanation;

    @JdbcTypeCode(SqlTypes.LONG32VARCHAR)
    private String evidence;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ValidationStatus validationStatus;

    @JdbcTypeCode(SqlTypes.JSON)
    private ValidationReport validationReport;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ReviewStatus reviewStatus;

    private boolean edited;

    /** TODO(#6): 검수한 사용자 */
    private Long reviewedBy;

    private String llmModel;

    public static Problem generated(Long workspaceId, Long profileId, PassageSource passage, Long generationJobId,
                                    QuestionType type, ProblemOptions options, AssembledProblem assembled,
                                    ValidationStatus validationStatus, ValidationReport report, String llmModel) {
        Problem p = new Problem();
        p.workspaceId = workspaceId;
        p.profileId = profileId;
        p.passageId = passage.passageId();
        p.passageTitle = passage.title();
        p.passageText = passage.text();
        p.generationJobId = generationJobId;
        p.type = type;
        p.options = options;
        p.applyGenerated(assembled, validationStatus, report, llmModel);
        return p;
    }

    /** 같은 지문·유형·옵션으로 다시 생성한 결과로 내용을 바꾼다 (ID는 유지, 검수 상태는 초기화) */
    public void regenerated(AssembledProblem assembled, ValidationStatus validationStatus, ValidationReport report,
                            String llmModel) {
        this.stem = null;
        this.conditions = new ArrayList<>();
        this.body = null;
        this.choices = new ArrayList<>();
        this.answer = null;
        this.answerText = null;
        this.explanation = null;
        this.evidence = null;
        this.edited = false;
        this.reviewedBy = null;
        applyGenerated(assembled, validationStatus, report, llmModel);
    }

    public PassageSource passageSource() {
        return new PassageSource(passageId, passageTitle, passageText);
    }

    private void applyGenerated(AssembledProblem assembled, ValidationStatus validationStatus, ValidationReport report,
                                String llmModel) {
        Problem p = this;
        if (assembled != null) {
            p.stem = assembled.stem();
            p.conditions = new ArrayList<>(assembled.conditions());
            p.body = assembled.body();
            p.choices = new ArrayList<>(assembled.choices());
            p.answer = assembled.answer();
            p.answerText = assembled.answerText();
            p.explanation = assembled.explanation();
            p.evidence = assembled.evidence();
        }
        p.validationStatus = validationStatus;
        p.validationReport = report;
        p.reviewStatus = ReviewStatus.DRAFT;
        p.llmModel = llmModel;
    }
}
