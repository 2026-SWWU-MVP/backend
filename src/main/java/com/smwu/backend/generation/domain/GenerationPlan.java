package com.smwu.backend.generation.domain;

import com.smwu.backend.pastexam.extraction.QuestionType;
import com.smwu.backend.problem.type.ProblemOptions;

import java.util.ArrayList;
import java.util.List;

/**
 * 생성 작업 계획 (요청 기록). 문항 순서는 지문 순서 → 유형 순서 → 같은 유형 안의 순번이다.
 *
 * @param passageIds 문제를 만들 지문 (순서대로)
 * @param perPassage 지문 1개당 만들 유형별 문항 수와 옵션
 */
public record GenerationPlan(List<Long> passageIds, List<TypeCount> perPassage) {

    public record TypeCount(QuestionType type, int count, ProblemOptions options) {
    }

    /** 문항 하나 = 생성 작업 하나 */
    public record Slot(int index, Long passageId, QuestionType type, ProblemOptions options) {
    }

    public List<Slot> slots() {
        List<Slot> slots = new ArrayList<>();
        for (Long passageId : passageIds) {
            for (TypeCount typeCount : perPassage) {
                for (int i = 0; i < typeCount.count(); i++) {
                    slots.add(new Slot(slots.size(), passageId, typeCount.type(), typeCount.options()));
                }
            }
        }
        return slots;
    }

    public int total() {
        return passageIds.size() * perPassage.stream().mapToInt(TypeCount::count).sum();
    }
}
