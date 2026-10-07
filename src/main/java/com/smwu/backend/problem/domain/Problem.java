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

import java.time.LocalDateTime;
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

    /** 생성 작업 안에서의 위치 (지문 순서 → 유형 순서). 병렬 생성이라 ID 순서와 다를 수 있어 정렬에 쓴다 */
    private Integer jobSlot;

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

    /** 마지막으로 채택·폐기·수정한 사용자 */
    private Long reviewedBy;

    private LocalDateTime reviewedAt;

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
        this.reviewedAt = null;
        applyGenerated(assembled, validationStatus, report, llmModel);
    }

    /**
     * 강사 수정 (#16). null인 항목은 그대로 둔다. 실제로 바뀐 항목이 있으면 edited.
     * 정답 구조(answer)는 검증용이라 그대로 두고, 정답지에 찍히는 answerText를 고친다.
     */
    public boolean edit(String stem, List<String> conditions, String body, List<String> choices, String answerText,
                        String explanation, Long userId) {
        boolean changed = false;
        if (stem != null && !stem.equals(this.stem)) {
            this.stem = stem;
            changed = true;
        }
        if (conditions != null && !conditions.equals(this.conditions)) {
            this.conditions = new ArrayList<>(conditions);
            changed = true;
        }
        if (body != null && !body.equals(this.body)) {
            this.body = body.isBlank() ? null : body;
            changed = true;
        }
        if (choices != null && !choices.equals(this.choices)) {
            this.choices = new ArrayList<>(choices);
            changed = true;
        }
        if (answerText != null && !answerText.equals(this.answerText)) {
            this.answerText = answerText;
            changed = true;
        }
        if (explanation != null && !explanation.equals(this.explanation)) {
            this.explanation = explanation;
            changed = true;
        }
        if (changed) {
            this.edited = true;
            markReviewed(userId);
        }
        return changed;
    }

    /** 채택(ACCEPTED) / 폐기(REJECTED) / 검수 전(DRAFT)으로 되돌리기 */
    public void review(ReviewStatus status, Long userId) {
        this.reviewStatus = status;
        markReviewed(userId);
    }

    private void markReviewed(Long userId) {
        this.reviewedBy = userId;
        this.reviewedAt = LocalDateTime.now();
    }

    /** 생성 작업의 몇 번째 문항인지 기록 */
    public void assignJobSlot(int slot) {
        this.jobSlot = slot;
    }

    /** 같은 지문·유형의 다음 문항을 만들 때 겹치지 않게 넘기는 용도 */
    public AssembledProblem toAssembled() {
        return new AssembledProblem(type, stem, List.copyOf(conditions), body, List.copyOf(choices), answer, answerText,
                explanation, evidence);
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
