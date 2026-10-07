package com.smwu.backend.worksheet.dto;

import com.smwu.backend.problem.dto.ProblemResponse;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;
import java.util.List;

/** 시험지 구성 API 요청과 응답 (#16) */
public final class WorksheetDtos {

    public static final int MAX_PROBLEMS = 50;

    private WorksheetDtos() {
    }

    /**
     * @param problemIds 채택한 문항 순서대로. 같은 지문의 문항은 저장할 때 첫 문항 위치로 모인다
     * @param showLogo   학원 로고를 머리글에 넣을지 (기본 true)
     */
    public record CreateWorksheetRequest(
            @NotBlank(message = "시험지 제목을 입력해 주세요.")
            @Size(max = 100, message = "시험지 제목은 100자 이하입니다.")
            String title,
            @Size(max = 200, message = "머리글은 200자 이하입니다.")
            String headerText,
            Boolean showLogo,
            @NotEmpty(message = "시험지에 넣을 문항을 골라 주세요.")
            @Size(max = MAX_PROBLEMS, message = "시험지에는 " + MAX_PROBLEMS + "문항까지 넣을 수 있습니다.")
            List<@NotNull Long> problemIds
    ) {
    }

    /** 보낸 항목만 바뀐다. problemIds를 보내면 문항 구성을 통째로 바꾼다 */
    public record UpdateWorksheetRequest(
            @Size(min = 1, max = 100, message = "시험지 제목은 1~100자입니다.")
            String title,
            @Size(max = 200, message = "머리글은 200자 이하입니다.")
            String headerText,
            Boolean showLogo,
            @Size(min = 1, max = MAX_PROBLEMS, message = "시험지에는 1~" + MAX_PROBLEMS + "문항을 넣을 수 있습니다.")
            List<@NotNull Long> problemIds
    ) {
    }

    public record WorksheetSummary(Long id, Long workspaceId, String title, String headerText, boolean showLogo,
                                   long problemCount, Long createdBy, String createdByName, LocalDateTime createdAt,
                                   LocalDateTime updatedAt) {
    }

    /**
     * 시험지 본문. 지문 하나 + 그 지문의 문항들(번호는 지문마다 1부터)이 순서대로 나온다.
     *
     * @param sections 지문 단위 묶음 (출력예시: 지문 하나 + 서술형 2~3문항)
     */
    public record WorksheetResponse(Long id, Long workspaceId, String title, String headerText, boolean showLogo,
                                    int problemCount, List<Section> sections, Long createdBy, String createdByName,
                                    LocalDateTime createdAt, LocalDateTime updatedAt) {
    }

    /** @param passageText 문항을 만들 때의 지문 사본 (원래 지문이 고쳐지거나 지워져도 시험지는 그대로) */
    public record Section(int order, Long passageId, String passageTitle, String passageText, List<Item> items) {
    }

    /** @param no 지문 안에서의 문항 번호 (1부터) */
    public record Item(int no, ProblemResponse problem) {
    }
}
