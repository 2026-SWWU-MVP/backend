package com.smwu.backend.problem.service;

import com.smwu.backend.pastexam.extraction.QuestionType;
import com.smwu.backend.problem.domain.ProblemReviewLog;
import com.smwu.backend.problem.domain.ProblemReviewLog.Action;
import com.smwu.backend.problem.domain.ProblemReviewLog.FieldChange;
import com.smwu.backend.problem.repository.ProblemReviewLogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 이 학원 강사의 검수 기록 → 생성 프롬프트의 "강사 검수 기록" 섹션 (#42, 쓸수록 맞춤).
 * 같은 워크스페이스·같은 유형의 최근 폐기 사유와 수정 방향, 채택한 문항을 유형마다 몇 줄로 요약한다.
 * 원문 지문은 넣지 않고, 학원 밖(학교 DB)으로 나가지 않는다.
 */
@Component
@RequiredArgsConstructor
public class TeacherPreferences {

    static final int RECENT_LOGS = 200;
    static final int MAX_LINES_PER_TYPE = 6;
    static final int MAX_ACCEPTED_PER_TYPE = 2;
    static final int MAX_TEXT = 80;

    private static final Map<String, String> FIELD_LABELS = Map.of(
            "stem", "발문", "conditions", "[조건]", "body", "본문", "choices", "[보기]", "answerText", "정답", "explanation", "해설");

    private final ProblemReviewLogRepository reviewLogRepository;

    public Map<QuestionType, List<String>> forWorkspace(Long workspaceId) {
        List<ProblemReviewLog> logs = reviewLogRepository.findRecent(workspaceId,
                List.of(Action.REJECTED, Action.EDITED, Action.ACCEPTED), PageRequest.of(0, RECENT_LOGS));
        Map<QuestionType, List<String>> lines = new EnumMap<>(QuestionType.class);
        Map<QuestionType, Integer> accepted = new EnumMap<>(QuestionType.class);
        for (ProblemReviewLog log : logs) {
            List<String> typeLines = lines.computeIfAbsent(log.getType(), t -> new ArrayList<>());
            if (typeLines.size() >= MAX_LINES_PER_TYPE) {
                continue;
            }
            String line = switch (log.getAction()) {
                case REJECTED -> "폐기" + (log.getReason() == null ? "" : " (사유: " + cut(log.getReason()) + ")")
                        + ": " + cut(log.getProblemSummary());
                case EDITED -> "수정: " + edits(log.getChanges());
                case ACCEPTED -> accepted.merge(log.getType(), 1, Integer::sum) <= MAX_ACCEPTED_PER_TYPE
                        ? "채택: " + cut(log.getProblemSummary()) : null;
            };
            if (line != null) {
                typeLines.add(line);
            }
        }
        lines.values().removeIf(List::isEmpty);
        return lines;
    }

    private static String edits(List<FieldChange> changes) {
        List<String> parts = new ArrayList<>();
        for (FieldChange c : changes) {
            parts.add(FIELD_LABELS.getOrDefault(c.field(), c.field()) + " \"" + cut(c.before()) + "\" → \"" + cut(c.after()) + "\"");
        }
        return String.join(", ", parts);
    }

    private static String cut(String text) {
        if (text == null || text.isBlank()) {
            return "(없음)";
        }
        String oneLine = text.replaceAll("\\s+", " ").strip();
        return oneLine.length() > MAX_TEXT ? oneLine.substring(0, MAX_TEXT) + "…" : oneLine;
    }
}
