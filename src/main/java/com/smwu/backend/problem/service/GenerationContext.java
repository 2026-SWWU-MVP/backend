package com.smwu.backend.problem.service;

import java.util.List;

/**
 * 문제 생성 프롬프트에 넣을 학교 맞춤 정보 (확정 프로필에서 만든다).
 *
 * @param profileId      사용한 프로필 버전 (재현성 기록)
 * @param teacherNotes   강사 의견 원문
 * @param teacherRules   [강사] 규칙 — 프롬프트의 "우선 적용" 섹션
 * @param pastExamRules  적용 중인 [기출] 규칙 (비활성화된 규칙 제외)
 * @param examples       대표 기출 문항 (few-shot)
 */
public record GenerationContext(
        Long profileId,
        List<String> teacherNotes,
        List<String> teacherRules,
        List<String> pastExamRules,
        List<Example> examples
) {

    public static GenerationContext empty() {
        return new GenerationContext(null, List.of(), List.of(), List.of(), List.of());
    }

    /** 대표 기출 문항 (프롬프트용 텍스트) */
    public record Example(String typeLabel, String text) {
    }
}
