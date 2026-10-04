package com.smwu.backend.profile.service;

import com.smwu.backend.pastexam.extraction.QuestionType;
import com.smwu.backend.profile.domain.ProfileRule;
import com.smwu.backend.profile.domain.ProfileRule.RuleSource;
import com.smwu.backend.profile.domain.TeacherNote;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 이전 버전과 새 버전의 차이를 강사가 읽을 문장으로 만든다 (changeSummary).
 * LLM 설명 대신 실제 바뀐 내용을 코드로 비교하므로 화면에 보이는 변경점과 데이터가 항상 일치한다.
 */
public final class ProfileDiff {

    static final String NO_CHANGE = "변경 사항 없음";

    private ProfileDiff() {
    }

    public record Snapshot(List<ProfileRule> rules, Map<QuestionType, Integer> typeMix, List<TeacherNote> notes,
                           List<Long> exampleQuestionIds) {
    }

    public static List<String> describe(Snapshot before, Snapshot after) {
        List<String> lines = new ArrayList<>();

        for (int i = before.notes().size(); i < after.notes().size(); i++) {
            TeacherNote note = after.notes().get(i);
            lines.add("강사 의견 추가: \"" + note.text() + "\"" + (note.persistent() ? " (다음 시험에도 적용)" : ""));
        }

        Map<String, ProfileRule> oldRules = before.rules().stream().collect(Collectors.toMap(ProfileRule::id, Function.identity()));
        Map<String, ProfileRule> newRules = after.rules().stream().collect(Collectors.toMap(ProfileRule::id, Function.identity()));
        for (ProfileRule rule : after.rules()) {
            ProfileRule old = oldRules.get(rule.id());
            if (old == null) {
                lines.add("규칙 추가 " + sourceLabel(rule) + ": " + rule.text());
                continue;
            }
            if (!old.text().equals(rule.text())) {
                lines.add("규칙 수정: \"" + old.text() + "\" → \"" + rule.text() + "\"");
            } else if (!old.evidenceQuestionIds().equals(rule.evidenceQuestionIds())) {
                lines.add("근거 문항 변경: " + rule.text());
            }
            if (!old.overridden() && rule.overridden()) {
                lines.add("이번 시험에서 비활성화: " + rule.text());
            } else if (old.overridden() && !rule.overridden()) {
                lines.add("다시 적용: " + rule.text());
            }
        }
        for (ProfileRule rule : before.rules()) {
            if (!newRules.containsKey(rule.id())) {
                lines.add("규칙 삭제 " + sourceLabel(rule) + ": " + rule.text());
            }
        }

        String mixChange = describeMix(before.typeMix(), after.typeMix());
        if (mixChange != null) {
            lines.add("지문당 유형 구성: " + mixChange);
        }
        if (!Objects.equals(before.exampleQuestionIds(), after.exampleQuestionIds())) {
            lines.add("대표 문항 변경 (" + before.exampleQuestionIds().size() + "개 → " + after.exampleQuestionIds().size() + "개)");
        }
        return lines.isEmpty() ? List.of(NO_CHANGE) : lines;
    }

    /** 바뀐 유형만 "어구 배열 1 → 2" 형식으로. 같으면 null */
    static String describeMix(Map<QuestionType, Integer> before, Map<QuestionType, Integer> after) {
        Set<QuestionType> types = EnumSet.noneOf(QuestionType.class);
        types.addAll(before.keySet());
        types.addAll(after.keySet());
        List<String> changes = new ArrayList<>();
        for (QuestionType type : types) {
            int from = before.getOrDefault(type, 0);
            int to = after.getOrDefault(type, 0);
            if (from != to) {
                changes.add(type.getLabel() + " " + from + " → " + to);
            }
        }
        return changes.isEmpty() ? null : String.join(", ", changes);
    }

    private static String sourceLabel(ProfileRule rule) {
        return rule.source().label();
    }
}
