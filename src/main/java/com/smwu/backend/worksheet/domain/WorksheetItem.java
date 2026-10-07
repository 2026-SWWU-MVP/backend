package com.smwu.backend.worksheet.domain;

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

/** 시험지 안의 문항 순서. 같은 지문의 문항은 붙어 있도록 저장할 때 정렬한다 */
@Entity
@Table(name = "worksheet_item", indexes = @Index(name = "idx_worksheet_item_worksheet", columnList = "worksheetId"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class WorksheetItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long worksheetId;

    @Column(nullable = false)
    private Long problemId;

    private int orderNo;

    public WorksheetItem(Long worksheetId, Long problemId, int orderNo) {
        this.worksheetId = worksheetId;
        this.problemId = problemId;
        this.orderNo = orderNo;
    }
}
