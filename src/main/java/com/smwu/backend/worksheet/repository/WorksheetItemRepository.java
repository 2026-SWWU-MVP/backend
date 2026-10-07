package com.smwu.backend.worksheet.repository;

import com.smwu.backend.worksheet.domain.WorksheetItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface WorksheetItemRepository extends JpaRepository<WorksheetItem, Long> {

    List<WorksheetItem> findByWorksheetIdOrderByOrderNo(Long worksheetId);

    @Modifying
    @Query("delete from WorksheetItem i where i.worksheetId = :worksheetId")
    void deleteByWorksheetId(@Param("worksheetId") Long worksheetId);

    /** 목록용: [worksheetId, 문항 수] */
    @Query("select i.worksheetId, count(i) from WorksheetItem i where i.worksheetId in :ids group by i.worksheetId")
    List<Object[]> countByWorksheet(@Param("ids") Collection<Long> worksheetIds);
}
