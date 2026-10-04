package com.smwu.backend.schooldb.repository;

import com.smwu.backend.pastexam.domain.ExamType;
import com.smwu.backend.schooldb.domain.SchoolExam;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SchoolExamRepository extends JpaRepository<SchoolExam, Long> {

    Optional<SchoolExam> findBySchoolIdAndGradeAndExamYearAndSemesterAndExamType(Long schoolId, int grade, int examYear,
                                                                                 int semester, ExamType examType);

    List<SchoolExam> findBySchoolIdAndGrade(Long schoolId, int grade);
}
