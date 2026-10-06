package com.smwu.backend.profile.controller;

import com.jayway.jsonpath.JsonPath;
import com.smwu.backend.document.TestPdfs;
import com.smwu.backend.support.TestWorkspaces;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;


import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 출제 프로필 강사 검토 루프 (Mock LLM).
 * v1 규칙(summarize-rules): r1 요약문 빈칸 조건, r2 어구 배열 / 유형 구성: 요약문 빈칸 2, 어구 배열 1
 * 의견 반영(apply-feedback): r1 비활성화(r99는 없는 규칙이라 무시), 강사 규칙 추가, 요약문 빈칸 1 + 어구 배열 2
 * 재검토(recheck-profile): r1 유지·문장 수정, 새 기출 규칙 추가, r2 삭제
 */
@SpringBootTest
@AutoConfigureMockMvc
class ProfileReviewApiTest {

    @Autowired
    private TestWorkspaces workspaces;

    @Autowired
    private MockMvc mockMvc;

    @Test
    void 의견_반영_확정_재검토_직접_수정_다시_분석() throws Exception {
        long workspaceId = workspaces.create();
        uploadAndExtract(workspaceId);
        String v1 = mockMvc.perform(post("/api/workspaces/{id}/profiles", workspaceId))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long v1Id = id(v1);

        mockMvc.perform(get("/api/workspaces/{id}/profiles/confirmed", workspaceId))
                .andExpect(status().isNotFound());

        // ③ 강사 의견 → v2
        String v2 = mockMvc.perform(post("/api/profiles/{id}/feedback", v1Id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\": \"학생들 말로는 이번 서술형은 어구 배열 위주로 낸다고 하심\", \"persistent\": true}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.parentId").value(v1Id))
                .andExpect(jsonPath("$.origin").value("TEACHER_FEEDBACK"))
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.teacherNotes[0].text").value("학생들 말로는 이번 서술형은 어구 배열 위주로 낸다고 하심"))
                .andExpect(jsonPath("$.teacherNotes[0].persistent").value(true))
                .andExpect(jsonPath("$.rules", hasSize(3)))
                .andExpect(jsonPath("$.rules[0].id").value("r1"))
                .andExpect(jsonPath("$.rules[0].overridden").value(true))
                .andExpect(jsonPath("$.rules[1].overridden").value(false))
                .andExpect(jsonPath("$.rules[2].id").value("r3"))
                .andExpect(jsonPath("$.rules[2].source").value("TEACHER"))
                .andExpect(jsonPath("$.rules[2].noteIndex").value(0))
                .andExpect(jsonPath("$.typeMixPerPassage.SUMMARY_BLANK").value(1))
                .andExpect(jsonPath("$.typeMixPerPassage.SENTENCE_ORDER").value(2))
                .andExpect(jsonPath("$.changeSummary", hasItems(
                        "v1에서 강사 의견 반영로 만든 버전입니다.",
                        "강사 의견 추가: \"학생들 말로는 이번 서술형은 어구 배열 위주로 낸다고 하심\" (다음 시험에도 적용)",
                        "규칙 추가 [강사]: 이번 시험 서술형은 어구 배열 위주로 출제된다.",
                        "지문당 유형 구성: 요약문 빈칸 2 → 1, 어구 배열 1 → 2")))
                .andReturn().getResponse().getContentAsString();
        long v2Id = id(v2);
        // 통계는 바뀌지 않고, v1은 그대로 남는다
        assertThat((Object) JsonPath.read(v2, "$.stats")).isEqualTo(JsonPath.read(v1, "$.stats"));
        mockMvc.perform(get("/api/profiles/{id}", v1Id))
                .andExpect(jsonPath("$.rules", hasSize(2)))
                .andExpect(jsonPath("$.rules[0].overridden").value(false));

        // ① 확정
        mockMvc.perform(post("/api/profiles/{id}/confirm", v2Id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.confirmedAt").isNotEmpty());
        mockMvc.perform(get("/api/workspaces/{id}/profiles/confirmed", workspaceId))
                .andExpect(jsonPath("$.version").value(2));

        // ② AI 재검토 → v3: r1은 문장만 고쳐지고 비활성화는 유지, r2 삭제, 새 기출 규칙 r4, 강사 규칙 r3 유지
        String v3 = mockMvc.perform(post("/api/profiles/{id}/recheck", v2Id))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value(3))
                .andExpect(jsonPath("$.origin").value("AI_RECHECK"))
                .andExpect(jsonPath("$.rules[0].id").value("r1"))
                .andExpect(jsonPath("$.rules[0].overridden").value(true))
                .andExpect(jsonPath("$.rules[1].id").value("r4"))
                .andExpect(jsonPath("$.rules[1].source").value("PAST_EXAM"))
                .andExpect(jsonPath("$.rules[2].id").value("r3"))
                .andExpect(jsonPath("$.rules[2].source").value("TEACHER"))
                .andExpect(jsonPath("$.typeMixPerPassage.SENTENCE_ORDER").value(2))
                .andExpect(jsonPath("$.changeSummary", hasItem(
                        "규칙 삭제 [기출]: 어구 배열은 분사구문이 포함된 본문 문장을 [보기] 어구로 나눠 출제한다.")))
                .andReturn().getResponse().getContentAsString();
        long v3Id = id(v3);

        // 재검토본을 확정하면 v2는 SUPERSEDED
        mockMvc.perform(post("/api/profiles/{id}/confirm", v3Id)).andExpect(jsonPath("$.status").value("CONFIRMED"));
        mockMvc.perform(get("/api/profiles/{id}", v2Id)).andExpect(jsonPath("$.status").value("SUPERSEDED"));

        // ④ 직접 수정 → v4: r1 다시 적용, r3·r4 삭제, 새 강사 규칙, 어구 배열 3
        mockMvc.perform(patch("/api/profiles/{id}", v3Id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"rules": [
                                   {"id": "r1", "text": "요약문 빈칸은 한 단어씩 쓰게 한다.", "overridden": false},
                                   {"text": "서술형 배점은 4~5점이다.", "category": "SCORING"}
                                 ],
                                 "typeMixPerPassage": {"SENTENCE_ORDER": 3, "SUMMARY_BLANK": 0}}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value(4))
                .andExpect(jsonPath("$.origin").value("MANUAL_EDIT"))
                .andExpect(jsonPath("$.rules", hasSize(2)))
                .andExpect(jsonPath("$.rules[0].overridden").value(false))
                .andExpect(jsonPath("$.rules[0].evidence", hasSize(1)))
                .andExpect(jsonPath("$.rules[1].id").value("r5"))
                .andExpect(jsonPath("$.rules[1].source").value("TEACHER"))
                .andExpect(jsonPath("$.typeMixPerPassage.SENTENCE_ORDER").value(3))
                .andExpect(jsonPath("$.typeMixPerPassage.SUMMARY_BLANK").doesNotExist())
                .andExpect(jsonPath("$.changeSummary", hasItems(
                        "다시 적용: 요약문 빈칸은 한 단어씩 쓰게 한다.",
                        "규칙 추가 [강사]: 서술형 배점은 4~5점이다.",
                        "지문당 유형 구성: 요약문 빈칸 1 → 0, 어구 배열 2 → 3")));

        // 다시 분석 → v5: 다음 시험에도 적용하기로 한 강사 의견과 그 의견의 강사 규칙은 유지
        mockMvc.perform(post("/api/workspaces/{id}/profiles", workspaceId))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value(5))
                .andExpect(jsonPath("$.origin").value("INITIAL_ANALYSIS"))
                .andExpect(jsonPath("$.teacherNotes", hasSize(1)))
                .andExpect(jsonPath("$.rules[2].source").value("TEACHER"))
                .andExpect(jsonPath("$.rules[2].text").value("이번 시험 서술형은 어구 배열 위주로 출제된다."))
                .andExpect(jsonPath("$.rules[2].noteIndex").value(0))
                .andExpect(jsonPath("$.changeSummary", hasItem("다음 시험에도 적용하기로 한 강사 의견 1개를 유지했습니다.")));
    }

    @Test
    void 직접_수정_검증() throws Exception {
        long workspaceId = workspaces.create();
        uploadAndExtract(workspaceId);
        long v1Id = id(mockMvc.perform(post("/api/workspaces/{id}/profiles", workspaceId))
                .andReturn().getResponse().getContentAsString());

        mockMvc.perform(patch("/api/profiles/{id}", v1Id).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"typeMixPerPassage\": {\"OBJ_BLANK\": 1}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("지문당 유형 구성에는 요약문 빈칸, 어구 배열, 어법 오류 수정, 조건 영작만 넣을 수 있습니다."));
        mockMvc.perform(patch("/api/profiles/{id}", v1Id).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"typeMixPerPassage\": {\"SUMMARY_BLANK\": 4, \"SENTENCE_ORDER\": 2}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("지문 1개당 문항 수 합계는 1~5입니다."));
        mockMvc.perform(patch("/api/profiles/{id}", v1Id).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rules\": [{\"id\": \"r9\", \"text\": \"없는 규칙\"}]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("없는 규칙입니다: r9"));
        mockMvc.perform(patch("/api/profiles/{id}", v1Id).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rules\": [{\"text\": \" \"}]}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/profiles/{id}/feedback", v1Id).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\": \"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("의견을 입력해 주세요."));

        // 실패한 요청은 버전을 만들지 않는다
        mockMvc.perform(get("/api/workspaces/{id}/profiles", workspaceId)).andExpect(jsonPath("$", hasSize(1)));
    }

    private static long id(String json) {
        return ((Number) JsonPath.read(json, "$.id")).longValue();
    }

    private void uploadAndExtract(long workspaceId) throws Exception {
        String uploaded = mockMvc.perform(multipart("/api/workspaces/{id}/past-exams", workspaceId)
                        .file(new MockMultipartFile("file", "mid.pdf", "application/pdf", TestPdfs.textPdf(1)))
                        .param("examYear", "2025").param("semester", "1").param("examType", "MIDTERM"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long examId = id(uploaded);
        mockMvc.perform(post("/api/past-exams/{id}/analyze", examId)).andExpect(status().isAccepted());
        String status = null;
        for (int i = 0; i < 100 && !"EXTRACTED".equals(status); i++) {
            Thread.sleep(100);
            status = JsonPath.read(mockMvc.perform(get("/api/past-exams/{id}", examId))
                    .andReturn().getResponse().getContentAsString(), "$.status");
        }
        assertThat(status).isEqualTo("EXTRACTED");
    }
}
