package com.smwu.backend.profile.dto;

import com.smwu.backend.pastexam.extraction.QuestionType;
import com.smwu.backend.profile.domain.ProfileOrigin;
import com.smwu.backend.profile.domain.ProfileRule.RuleCategory;
import com.smwu.backend.profile.domain.ProfileRule.RuleSource;
import com.smwu.backend.profile.domain.ProfileStats;
import com.smwu.backend.profile.domain.ProfileStatus;
import com.smwu.backend.profile.domain.TeacherNote;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 출제 프로필 상세 (강사 검토 화면용). 근거 문항과 대표 문항은 "2025년 1학기 중간고사 서답형 2번"처럼 표시용 이름을 함께 준다.
 *
 * @param stats             기출 집계 (사실, 수정 불가)
 * @param rules             출제 규칙. 출처(PAST_EXAM/TEACHER)와 근거 문항 포함
 * @param typeMixPerPassage 지문 1개당 기본 유형 구성
 * @param examples          few-shot 예시로 쓸 대표 서답형 기출 문항
 * @param sourceExams       분석에 쓴 기출 시험지
 * @param changeSummary     이전 버전 대비 바뀐 점
 * @param teacherNotes      강사 의견 원문과 작성자
 * @param createdByName     이 버전을 만든 사람 (로그인 기능 이전 데이터는 null)
 * @param confirmedByName   확정한 사람
 */
public record ProfileResponse(
        Long id,
        Long workspaceId,
        int version,
        Long parentId,
        ProfileStatus status,
        ProfileOrigin origin,
        ProfileStats stats,
        List<RuleView> rules,
        Map<QuestionType, Integer> typeMixPerPassage,
        List<NoteView> teacherNotes,
        List<String> changeSummary,
        List<ExampleView> examples,
        List<ExamRef> sourceExams,
        String llmModel,
        LocalDateTime createdAt,
        LocalDateTime confirmedAt,
        Long createdBy,
        String createdByName,
        Long confirmedBy,
        String confirmedByName
) {

    /** @param persistent 다음 시험에도 적용하는 의견이면 true */
    public record NoteView(String text, boolean persistent, Long createdBy, String createdByName) {

        public static NoteView of(TeacherNote note, String createdByName) {
            return new NoteView(note.text(), note.persistent(), note.createdBy(), createdByName);
        }
    }

    public record RuleView(
            String id,
            String text,
            RuleCategory category,
            RuleSource source,
            List<QuestionRef> evidence,
            boolean overridden,
            Integer noteIndex
    ) {
    }

    /** @param label 예) 2025년 1학기 중간고사 서답형 2번. 기출이 삭제되었으면 "삭제된 기출 문항" */
    public record QuestionRef(Long id, String label) {
    }

    public record ExampleView(
            Long id,
            String label,
            QuestionType type,
            String stem,
            String body,
            List<String> conditions,
            List<String> choices,
            String answer,
            Double points
    ) {
    }

    public record ExamRef(Long id, String title) {
    }
}
