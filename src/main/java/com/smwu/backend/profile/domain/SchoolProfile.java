package com.smwu.backend.profile.domain;

import com.smwu.backend.common.domain.BaseTimeEntity;
import com.smwu.backend.pastexam.extraction.QuestionType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 워크스페이스(학교 + 학년)의 출제 프로필 한 버전.
 * 프로필은 수정하지 않고 항상 새 버전을 만든다 (생성된 문제가 어떤 버전으로 만들어졌는지 추적하기 위해).
 */
@Entity
@Table(name = "school_profile",
        uniqueConstraints = @UniqueConstraint(name = "uk_school_profile_version", columnNames = {"workspaceId", "version"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SchoolProfile extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long workspaceId;

    @Column(nullable = false)
    private int version;

    /** 이전 버전 ID. 첫 버전이면 null */
    private Long parentId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ProfileStatus status;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private ProfileOrigin origin;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private ProfileStats stats;

    @JdbcTypeCode(SqlTypes.JSON)
    private List<ProfileRule> rules = new ArrayList<>();

    /** 지문 1개당 기본 유형 구성 (문제 생성 화면의 기본값) */
    @JdbcTypeCode(SqlTypes.JSON)
    private Map<QuestionType, Integer> typeMixPerPassage = new LinkedHashMap<>();

    @JdbcTypeCode(SqlTypes.JSON)
    private List<TeacherNote> teacherNotes = new ArrayList<>();

    /** 이전 버전 대비 바뀐 점 */
    @JdbcTypeCode(SqlTypes.JSON)
    private List<String> changeSummary = new ArrayList<>();

    /** few-shot 예시로 쓸 대표 서답형 기출 문항 ID */
    @JdbcTypeCode(SqlTypes.JSON)
    private List<Long> exampleQuestionIds = new ArrayList<>();

    /** 분석에 사용한 기출 시험지 ID */
    @JdbcTypeCode(SqlTypes.JSON)
    private List<Long> sourceExamIds = new ArrayList<>();

    /** 규칙 요약에 쓴 모델 (LLM을 호출하지 않았으면 null) */
    private String llmModel;

    /** TODO(#6): 로그인 사용자 ID */
    private Long createdBy;

    private Long confirmedBy;

    private LocalDateTime confirmedAt;

    @Builder
    private SchoolProfile(Long workspaceId, int version, Long parentId, ProfileOrigin origin, ProfileStats stats,
                          List<ProfileRule> rules, Map<QuestionType, Integer> typeMixPerPassage,
                          List<TeacherNote> teacherNotes, List<String> changeSummary, List<Long> exampleQuestionIds,
                          List<Long> sourceExamIds, String llmModel, Long createdBy) {
        this.workspaceId = workspaceId;
        this.version = version;
        this.parentId = parentId;
        this.status = ProfileStatus.DRAFT;
        this.origin = origin;
        this.stats = stats;
        this.rules = new ArrayList<>(rules);
        this.typeMixPerPassage = new LinkedHashMap<>(typeMixPerPassage);
        this.teacherNotes = teacherNotes == null ? new ArrayList<>() : new ArrayList<>(teacherNotes);
        this.changeSummary = changeSummary == null ? new ArrayList<>() : new ArrayList<>(changeSummary);
        this.exampleQuestionIds = new ArrayList<>(exampleQuestionIds);
        this.sourceExamIds = new ArrayList<>(sourceExamIds);
        this.llmModel = llmModel;
        this.createdBy = createdBy;
    }
}
