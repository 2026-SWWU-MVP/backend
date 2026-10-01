package com.smwu.backend.pastexam.domain;

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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** 기출에서 추출한 지문. 문항은 code(P1, P2 ...)로 참조한다 */
@Entity
@Table(name = "past_passage", indexes = @Index(name = "idx_past_passage_exam", columnList = "pastExamId"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PastPassage extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long pastExamId;

    /** 시험지 안에서의 지문 id (P1, P2 ...) */
    @Column(nullable = false, length = 20)
    private String code;

    private int orderNo;

    private String title;

    /** 지문 본문. 밑줄은 대괄호 규칙 */
    @JdbcTypeCode(SqlTypes.LONG32VARCHAR)
    @Column(nullable = false)
    private String text;

    private boolean edited;

    public PastPassage(Long pastExamId, String code, int orderNo, String title, String text) {
        this.pastExamId = pastExamId;
        this.code = code;
        this.orderNo = orderNo;
        this.title = title;
        this.text = text;
    }

    public void edit(String title, String text) {
        if (title != null) {
            this.title = title.isBlank() ? null : title;
        }
        if (text != null) {
            this.text = text;
        }
        this.edited = true;
    }
}
