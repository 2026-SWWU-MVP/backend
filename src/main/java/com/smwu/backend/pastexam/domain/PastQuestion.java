package com.smwu.backend.pastexam.domain;

import com.smwu.backend.common.domain.BaseTimeEntity;
import com.smwu.backend.pastexam.extraction.ExtractedExam.QuestionSection;
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
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.ArrayList;
import java.util.List;

/**
 * 기출에서 추출한 문항. 서답형 번호가 객관식 번호와 겹치는 시험지가 많아 (section, no)로 식별한다.
 * 수정하면 edited=true가 되어 다시 추출해도 사람이 고친 문항인지 알 수 있다.
 */
@Entity
@Table(name = "past_question", indexes = @Index(name = "idx_past_question_exam", columnList = "pastExamId"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PastQuestion extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long pastExamId;

    /** 시험지 안에서의 순서 (객관식과 서답형이 섞여 있어도 원본 순서 유지) */
    private int orderNo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private QuestionSection section;

    private int no;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private QuestionType type;

    /** 참조 지문 code 목록 (P1, P2 ...) */
    @JdbcTypeCode(SqlTypes.JSON)
    private List<String> passageCodes = new ArrayList<>();

    @Column(nullable = false, length = 2000)
    private String stem;

    @JdbcTypeCode(SqlTypes.LONG32VARCHAR)
    private String body;

    @JdbcTypeCode(SqlTypes.JSON)
    private List<String> conditions = new ArrayList<>();

    @JdbcTypeCode(SqlTypes.JSON)
    private List<String> choices = new ArrayList<>();

    /** 시험지에 인쇄된 정답·모범답안. 없으면 null */
    @Column(length = 4000)
    private String answer;

    private Double points;

    private boolean edited;

    @Builder
    private PastQuestion(Long pastExamId, int orderNo, QuestionSection section, int no, QuestionType type,
                         List<String> passageCodes, String stem, String body, List<String> conditions,
                         List<String> choices, String answer, Double points) {
        this.pastExamId = pastExamId;
        this.orderNo = orderNo;
        this.section = section;
        this.no = no;
        this.type = type;
        this.passageCodes = new ArrayList<>(passageCodes);
        this.stem = stem;
        this.body = body;
        this.conditions = new ArrayList<>(conditions);
        this.choices = new ArrayList<>(choices);
        this.answer = answer;
        this.points = points;
    }

    /** null인 항목은 그대로 둔다. body·answer·points를 비우려면 빈 문자열 / clearPoints 사용 */
    public void edit(QuestionSection section, Integer no, QuestionType type, List<String> passageCodes, String stem,
                     String body, List<String> conditions, List<String> choices, String answer, Double points,
                     boolean clearPoints) {
        if (section != null) {
            this.section = section;
        }
        if (no != null) {
            this.no = no;
        }
        if (type != null) {
            this.type = type;
        }
        if (passageCodes != null) {
            this.passageCodes = new ArrayList<>(passageCodes);
        }
        if (stem != null) {
            this.stem = stem;
        }
        if (body != null) {
            this.body = body.isBlank() ? null : body;
        }
        if (conditions != null) {
            this.conditions = new ArrayList<>(conditions);
        }
        if (choices != null) {
            this.choices = new ArrayList<>(choices);
        }
        if (answer != null) {
            this.answer = answer.isBlank() ? null : answer;
        }
        if (clearPoints) {
            this.points = null;
        } else if (points != null) {
            this.points = points;
        }
        this.edited = true;
    }
}
