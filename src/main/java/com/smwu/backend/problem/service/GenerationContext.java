package com.smwu.backend.problem.service;

import com.smwu.backend.pastexam.extraction.QuestionType;

import java.util.List;
import java.util.Map;

/**
 * 문제 생성 프롬프트에 넣을 학교 맞춤 정보 (확정 프로필에서 만든다).
 *
 * @param profileId      사용한 프로필 버전 (재현성 기록)
 * @param teacherNotes   강사 의견 원문
 * @param teacherRules   [강사] 규칙 — 프롬프트의 "우선 적용" 섹션
 * @param pastExamRules  적용 중인 [기출] 규칙 (비활성화된 규칙 제외)
 * @param examples       대표 기출 문항 (few-shot)
 * @param teacherReviews 유형별 이 학원 강사의 검수 기록 (폐기 사유·수정 방향·채택, #42)
 */
public record GenerationContext(
        Long profileId,
        List<String> teacherNotes,
        List<String> teacherRules,
        List<String> pastExamRules,
        List<Example> examples,
        Map<QuestionType, List<String>> teacherReviews
) {

    /** 강사 검수 기록 없이 */
    public GenerationContext(Long profileId, List<String> teacherNotes, List<String> teacherRules, List<String> pastExamRules,
                             List<Example> examples) {
        this(profileId, teacherNotes, teacherRules, pastExamRules, examples, Map.of());
    }

    public static GenerationContext empty() {
        return new GenerationContext(null, List.of(), List.of(), List.of(), List.of());
    }

    /** 이 유형의 강사 검수 기록 (#42). 없으면 빈 목록 */
    public List<String> teacherReviews(QuestionType type) {
        return teacherReviews == null ? List.of() : teacherReviews.getOrDefault(type, List.of());
    }

    /** 대표 기출 문항 (프롬프트용 텍스트) */
    public record Example(String typeLabel, String text) {
    }
}
