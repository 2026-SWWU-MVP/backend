package com.smwu.backend.pastexam.repository;

import com.smwu.backend.pastexam.domain.PastPassage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface PastPassageRepository extends JpaRepository<PastPassage, Long> {

    List<PastPassage> findByPastExamIdOrderByOrderNo(Long pastExamId);

    @Modifying
    @Query("delete from PastPassage p where p.pastExamId = :pastExamId")
    void deleteByPastExamId(Long pastExamId);
}
