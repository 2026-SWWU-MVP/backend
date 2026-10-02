package com.smwu.backend.pastexam.repository;

import com.smwu.backend.pastexam.domain.PastQuestion;
import com.smwu.backend.pastexam.extraction.ExtractedExam.QuestionSection;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.List;

public interface PastQuestionRepository extends JpaRepository<PastQuestion, Long> {

    List<PastQuestion> findByPastExamIdOrderByOrderNo(Long pastExamId);

    List<PastQuestion> findByPastExamIdIn(Collection<Long> pastExamIds);

    /** 목록 화면용 문항 수 집계: [pastExamId, section, count] */
    @Query("select q.pastExamId, q.section, count(q) from PastQuestion q where q.pastExamId in :examIds group by q.pastExamId, q.section")
    List<Object[]> countBySection(Collection<Long> examIds);

    long countByPastExamIdAndSection(Long pastExamId, QuestionSection section);

    @Modifying
    @Query("delete from PastQuestion q where q.pastExamId = :pastExamId")
    void deleteByPastExamId(Long pastExamId);
}
