package com.smwu.backend.generation.dto;

import com.smwu.backend.pastexam.extraction.QuestionType;
import com.smwu.backend.problem.type.ProblemOptions;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 문제 생성 요청.
 *
 * @param profileId  확정 프로필 ID (DRAFT면 409)
 * @param passageIds 문제를 만들 지문 (이 순서대로 시험지에 들어간다)
 * @param perPassage 지문 1개당 유형별 문항 수. 비우면 프로필의 지문당 유형 구성(typeMixPerPassage)을 쓴다
 */
public record CreateGenerationJobRequest(
        @NotNull(message = "프로필을 선택해 주세요.") Long profileId,
        @NotEmpty(message = "지문을 1개 이상 선택해 주세요.")
        @Size(max = 20, message = "지문은 한 번에 20개까지 선택할 수 있습니다.")
        List<@NotNull Long> passageIds,
        @Valid @Size(max = 4, message = "유형은 4개까지입니다.") List<TypeCountRequest> perPassage
) {

    /**
     * @param options 유형별 옵션 (요약문 빈칸: blankCount, firstLetterHint / 어구 배열: minWords / 어법 오류 수정: errorCount)
     */
    public record TypeCountRequest(
            @NotNull(message = "유형을 선택해 주세요.") QuestionType type,
            @NotNull @Min(value = 1, message = "유형별 문항 수는 1~5입니다.") @Max(value = 5, message = "유형별 문항 수는 1~5입니다.")
            Integer count,
            ProblemOptions options
    ) {
    }
}
