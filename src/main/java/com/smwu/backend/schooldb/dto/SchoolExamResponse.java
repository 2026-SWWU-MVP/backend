package com.smwu.backend.schooldb.dto;

import com.smwu.backend.pastexam.domain.ExamType;
import com.smwu.backend.schooldb.domain.ExamRoundStats;
import com.smwu.backend.schooldb.domain.SchoolExam;

/**
 * 학교 DB의 기출 회차. 원문과 올린 학원은 보이지 않는다.
 *
 * @param contributorCount 이 회차를 올린 학원 수
 */
public record SchoolExamResponse(
        Long id,
        Long schoolId,
        int grade,
        int examYear,
        int semester,
        ExamType examType,
        int contributorCount,
        ExamRoundStats stats
) {

    public static SchoolExamResponse of(SchoolExam e) {
        return new SchoolExamResponse(e.getId(), e.getSchoolId(), e.getGrade(), e.getExamYear(), e.getSemester(), e.getExamType(),
                e.getContributorCount(), e.getStats());
    }
}
