package com.smwu.backend.profile.controller;

import com.jayway.jsonpath.JsonPath;
import com.smwu.backend.document.TestPdfs;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 기출 업로드·추출(Mock) → 출제 프로필 생성·조회 API.
 * Mock 응답: mock-llm/extract-questions.json (객관식 1 + 서답형 2), mock-llm/summarize-rules.json (규칙 3개 중 1개는 근거 없음)
 */
@SpringBootTest
@AutoConfigureMockMvc
class ProfileApiTest {

    private static final AtomicLong WORKSPACE_IDS = new AtomicLong(5000);

    @Autowired
    private MockMvc mockMvc;

    @Test
    void 기출로_프로필을_만들고_다시_분석하면_새_버전이_된다() throws Exception {
        long workspaceId = WORKSPACE_IDS.incrementAndGet();
        uploadAndExtract(workspaceId);

        String created = mockMvc.perform(post("/api/workspaces/{id}/profiles", workspaceId))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.parentId").doesNotExist())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.origin").value("INITIAL_ANALYSIS"))
                // 통계: 코드 집계 (배점 3.5 / 4 / 5 → 서답형 비중 9/12.5 = 0.72)
                .andExpect(jsonPath("$.stats.examCount").value(1))
                .andExpect(jsonPath("$.stats.totalQuestions").value(3))
                .andExpect(jsonPath("$.stats.subjectiveCount").value(2))
                .andExpect(jsonPath("$.stats.subjectiveRatio").value(0.667))
                .andExpect(jsonPath("$.stats.subjectivePointsRatio").value(0.72))
                .andExpect(jsonPath("$.stats.typeCounts.OBJ_GRAMMAR").value(1))
                // 규칙: 근거 없는 1개는 제거, 근거 문항은 표시용 이름과 함께
                .andExpect(jsonPath("$.rules", hasSize(2)))
                .andExpect(jsonPath("$.rules[0].id").value("r1"))
                .andExpect(jsonPath("$.rules[0].source").value("PAST_EXAM"))
                .andExpect(jsonPath("$.rules[0].evidence[0].label").value("2025년 1학기 중간고사 서답형 1번"))
                .andExpect(jsonPath("$.rules[1].evidence[0].label").value("2025년 1학기 중간고사 서답형 2번"))
                // 대표 문항: 어구 배열(Q2) → 요약 빈칸(Q1)
                .andExpect(jsonPath("$.examples", hasSize(2)))
                .andExpect(jsonPath("$.examples[0].type").value("SENTENCE_ORDER"))
                .andExpect(jsonPath("$.examples[0].choices", hasSize(8)))
                // 지문당 유형 구성: 기출 서답형 요약 빈칸 1, 어구 배열 1 → 3문항을 2:1
                .andExpect(jsonPath("$.typeMixPerPassage.SUMMARY_BLANK").value(2))
                .andExpect(jsonPath("$.typeMixPerPassage.SENTENCE_ORDER").value(1))
                .andExpect(jsonPath("$.sourceExams[0].title").value("2025년 1학기 중간고사"))
                .andExpect(jsonPath("$.changeSummary[0]", startsWith("기출 1개(2025년 1학기 중간고사)로 첫 출제 프로필")))
                .andExpect(jsonPath("$.llmModel").value("mock"))
                .andReturn().getResponse().getContentAsString();
        long firstId = ((Number) JsonPath.read(created, "$.id")).longValue();

        // 같은 기출로 다시 분석 → v2, 통계는 같다
        String second = mockMvc.perform(post("/api/workspaces/{id}/profiles", workspaceId))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.parentId").value(firstId))
                .andExpect(jsonPath("$.changeSummary[0]", startsWith("기출 1개(2025년 1학기 중간고사)로 다시 분석했습니다. (이전 v1")))
                .andReturn().getResponse().getContentAsString();
        assertThat((Object) JsonPath.read(second, "$.stats")).isEqualTo(JsonPath.read(created, "$.stats"));

        mockMvc.perform(get("/api/workspaces/{id}/profiles", workspaceId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].version").value(2))
                .andExpect(jsonPath("$[0].ruleCount").value(2))
                .andExpect(jsonPath("$[1].version").value(1));

        mockMvc.perform(get("/api/profiles/{id}", firstId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.rules", hasSize(2)));
    }

    @Test
    void 추출된_기출이_없으면_409() throws Exception {
        mockMvc.perform(post("/api/workspaces/{id}/profiles", WORKSPACE_IDS.incrementAndGet()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("NO_EXTRACTED_PAST_EXAM"));
    }

    @Test
    void 없는_프로필은_404() throws Exception {
        mockMvc.perform(get("/api/profiles/{id}", 987654))
                .andExpect(status().isNotFound());
    }

    private void uploadAndExtract(long workspaceId) throws Exception {
        String uploaded = mockMvc.perform(multipart("/api/workspaces/{id}/past-exams", workspaceId)
                        .file(new MockMultipartFile("file", "mid.pdf", "application/pdf", TestPdfs.textPdf(1)))
                        .param("examYear", "2025").param("semester", "1").param("examType", "MIDTERM"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long examId = ((Number) JsonPath.read(uploaded, "$.id")).longValue();
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
