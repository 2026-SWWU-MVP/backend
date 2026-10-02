package com.smwu.backend.profile.dto;

import com.smwu.backend.pastexam.extraction.QuestionType;
import com.smwu.backend.profile.domain.ProfileRule.RuleCategory;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Map;

/**
 * 강사 직접 수정. 보낸 항목만 바뀐다 (null이면 그대로).
 *
 * @param rules             수정 후 규칙 전체 목록. 기존 규칙은 id를 넣어 보내고(문장·분류·비활성화 수정 가능, 근거는 유지),
 *                          id 없이 보내면 새 [강사] 규칙, 목록에서 빠진 규칙은 삭제
 * @param typeMixPerPassage 지문당 유형 구성 전체 (요약문 빈칸·어구 배열·어법 오류 수정·조건 영작, 합계 1~5)
 */
public record ManualEditRequest(
        @Valid @Size(max = 20, message = "규칙은 20개까지입니다.") List<RuleEdit> rules,
        Map<QuestionType, Integer> typeMixPerPassage
) {

    public record RuleEdit(
            String id,
            @NotBlank(message = "규칙 문장을 입력해 주세요.")
            @Size(max = 300, message = "규칙은 300자 이하입니다.")
            String text,
            RuleCategory category,
            Boolean overridden
    ) {
    }
}
