package com.smwu.backend.generation;

import com.jayway.jsonpath.JsonPath;
import com.smwu.backend.document.TestPdfs;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 생성 작업 API (Mock LLM). Mock 문제 응답은 지문 A(lifeless/revitalize)와 지문 B(However, having a limited budget ...)에만 맞는다.
 * 그래서 A×요약문 빈칸, B×어구 배열은 PASSED, 나머지 조합은 규칙 검증 FAILED → 일부 실패해도 나머지가 저장되는지 확인할 수 있다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class GenerationJobApiTest {

    private static final AtomicLong WORKSPACE_IDS = new AtomicLong(15000);
    private static final String PASSAGES = """
            # Bringing New Life to Old Cities
            As cities age, neighborhoods can become old and lifeless, which may cause citizens to move away. When this happens, a collaboration between the local government and citizens is an effective way to revitalize the area.
            ---
            For years, the neighborhood was known for its high crime rates. However, having a limited budget, the government was unable to do so and had to come up with a new plan.
            """;

    @Autowired
    private MockMvc mockMvc;

    @Test
    void 지문_2개_유형_2개로_4문항을_병렬_생성하고_순서대로_돌려준다() throws Exception {
        long workspaceId = WORKSPACE_IDS.incrementAndGet();
        long profileId = confirmedProfile(workspaceId);
        List<Long> passageIds = passages(workspaceId);

        String created = mockMvc.perform(post("/api/workspaces/{id}/generation-jobs", workspaceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"profileId": %d, "passageIds": [%d, %d],
                                 "perPassage": [{"type": "SUMMARY_BLANK", "count": 1, "options": {"blankCount": 2}},
                                                {"type": "SENTENCE_ORDER", "count": 1}]}
                                """.formatted(profileId, passageIds.get(0), passageIds.get(1))))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("RUNNING"))
                .andExpect(jsonPath("$.total").value(4))
                .andExpect(jsonPath("$.profileId").value(profileId))
                .andReturn().getResponse().getContentAsString();
        long jobId = id(created);
        String job = waitUntilDone(jobId);

        assertThat((String) JsonPath.read(job, "$.status")).isEqualTo("COMPLETED");
        assertThat((Integer) JsonPath.read(job, "$.completed")).isEqualTo(4);
        assertThat((Integer) JsonPath.read(job, "$.progress")).isEqualTo(100);
        assertThat(((Number) JsonPath.read(job, "$.passed")).intValue()).isEqualTo(2);
        assertThat(((Number) JsonPath.read(job, "$.failed")).intValue()).isEqualTo(2);
        assertThat((String) JsonPath.read(job, "$.finishedAt")).isNotNull();

        mockMvc.perform(get("/api/generation-jobs/{id}/problems", jobId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(4)))
                // 지문 A → 지문 B, 각 지문 안에서 요약문 빈칸 → 어구 배열
                .andExpect(jsonPath("$[0].type").value("SUMMARY_BLANK"))
                .andExpect(jsonPath("$[0].passageId").value(passageIds.get(0)))
                .andExpect(jsonPath("$[0].validationStatus").value("PASSED"))
                .andExpect(jsonPath("$[1].type").value("SENTENCE_ORDER"))
                .andExpect(jsonPath("$[1].validationStatus").value("FAILED"))
                .andExpect(jsonPath("$[2].type").value("SUMMARY_BLANK"))
                .andExpect(jsonPath("$[2].passageId").value(passageIds.get(1)))
                .andExpect(jsonPath("$[2].validationStatus").value("FAILED"))
                .andExpect(jsonPath("$[3].type").value("SENTENCE_ORDER"))
                .andExpect(jsonPath("$[3].validationStatus").value("PASSED"))
                .andExpect(jsonPath("$[3].generationJobId").value(jobId))
                .andExpect(jsonPath("$[3].passageTitle").doesNotExist());

        mockMvc.perform(get("/api/workspaces/{id}/generation-jobs", workspaceId))
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id").value(jobId));
    }

    @Test
    void 유형_구성을_비우면_프로필의_지문당_유형_구성을_쓴다() throws Exception {
        long workspaceId = WORKSPACE_IDS.incrementAndGet();
        long profileId = confirmedProfile(workspaceId);
        List<Long> passageIds = passages(workspaceId);

        // Mock 프로필: 요약문 빈칸 2 + 어구 배열 1 → 지문 1개에 3문항
        long jobId = id(mockMvc.perform(post("/api/workspaces/{id}/generation-jobs", workspaceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"profileId\": %d, \"passageIds\": [%d]}".formatted(profileId, passageIds.get(0))))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.plan.perPassage[0].type").value("SUMMARY_BLANK"))
                .andExpect(jsonPath("$.plan.perPassage[0].count").value(2))
                .andReturn().getResponse().getContentAsString());
        waitUntilDone(jobId);

        mockMvc.perform(get("/api/generation-jobs/{id}/problems", jobId))
                .andExpect(jsonPath("$", hasSize(3)))
                .andExpect(jsonPath("$[0].type").value("SUMMARY_BLANK"))
                .andExpect(jsonPath("$[1].type").value("SUMMARY_BLANK"))
                .andExpect(jsonPath("$[2].type").value("SENTENCE_ORDER"));
    }

    @Test
    void 잘못된_요청() throws Exception {
        long workspaceId = WORKSPACE_IDS.incrementAndGet();
        long profileId = confirmedProfile(workspaceId);
        List<Long> passageIds = passages(workspaceId);
        long otherWorkspace = WORKSPACE_IDS.incrementAndGet();
        List<Long> otherPassages = passages(otherWorkspace);

        // 다른 워크스페이스의 지문
        create(workspaceId, "{\"profileId\": %d, \"passageIds\": [%d]}".formatted(profileId, otherPassages.get(0)))
                .andExpect(status().isBadRequest());
        // 같은 지문 두 번
        create(workspaceId, "{\"profileId\": %d, \"passageIds\": [%d, %d]}".formatted(profileId, passageIds.get(0), passageIds.get(0)))
                .andExpect(status().isBadRequest());
        // 지원하지 않는 유형
        create(workspaceId, "{\"profileId\": %d, \"passageIds\": [%d], \"perPassage\": [{\"type\": \"SUBJ_SHORT_ANSWER\", \"count\": 1}]}"
                .formatted(profileId, passageIds.get(0)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("아직 생성할 수 없는 유형입니다: 단답형"));
        // 지문 없음, 문항 수 범위
        create(workspaceId, "{\"profileId\": %d, \"passageIds\": []}".formatted(profileId)).andExpect(status().isBadRequest());
        create(workspaceId, "{\"profileId\": %d, \"passageIds\": [%d], \"perPassage\": [{\"type\": \"SUMMARY_BLANK\", \"count\": 9}]}"
                .formatted(profileId, passageIds.get(0)))
                .andExpect(status().isBadRequest());
        // 다른 워크스페이스의 프로필
        create(otherWorkspace, "{\"profileId\": %d, \"passageIds\": [%d]}".formatted(profileId, otherPassages.get(0)))
                .andExpect(status().isNotFound());
        // 확정 안 된 프로필
        long draftId = id(mockMvc.perform(post("/api/workspaces/{id}/profiles", workspaceId)).andReturn().getResponse().getContentAsString());
        create(workspaceId, "{\"profileId\": %d, \"passageIds\": [%d]}".formatted(draftId, passageIds.get(0)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PROFILE_NOT_CONFIRMED"));

        mockMvc.perform(get("/api/workspaces/{id}/generation-jobs", workspaceId)).andExpect(jsonPath("$", hasSize(0)));
        mockMvc.perform(get("/api/generation-jobs/{id}", 99999999)).andExpect(status().isNotFound());
    }

    private org.springframework.test.web.servlet.ResultActions create(long workspaceId, String body) throws Exception {
        return mockMvc.perform(post("/api/workspaces/{id}/generation-jobs", workspaceId)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private String waitUntilDone(long jobId) throws Exception {
        String job = null;
        for (int i = 0; i < 150; i++) {
            Thread.sleep(100);
            job = mockMvc.perform(get("/api/generation-jobs/{id}", jobId)).andReturn().getResponse().getContentAsString();
            if (!"RUNNING".equals(JsonPath.read(job, "$.status"))) {
                break;
            }
        }
        return job;
    }

    private List<Long> passages(long workspaceId) throws Exception {
        String material = mockMvc.perform(post("/api/workspaces/{id}/materials/text", workspaceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(new tools.jackson.databind.json.JsonMapper().writeValueAsString(
                                java.util.Map.of("title", "교과서 2과", "text", PASSAGES))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String passages = mockMvc.perform(get("/api/materials/{id}/passages", id(material))).andReturn().getResponse().getContentAsString();
        List<Number> ids = JsonPath.read(passages, "$[*].id");
        return ids.stream().map(Number::longValue).toList();
    }

    private long confirmedProfile(long workspaceId) throws Exception {
        long examId = id(mockMvc.perform(multipart("/api/workspaces/{id}/past-exams", workspaceId)
                        .file(new MockMultipartFile("file", "mid.pdf", "application/pdf", TestPdfs.textPdf(1)))
                        .param("examYear", "2025").param("semester", "1").param("examType", "MIDTERM"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        mockMvc.perform(post("/api/past-exams/{id}/analyze", examId)).andExpect(status().isAccepted());
        String state = null;
        for (int i = 0; i < 100 && !"EXTRACTED".equals(state); i++) {
            Thread.sleep(100);
            state = JsonPath.read(mockMvc.perform(get("/api/past-exams/{id}", examId)).andReturn().getResponse().getContentAsString(), "$.status");
        }
        long profileId = id(mockMvc.perform(post("/api/workspaces/{id}/profiles", workspaceId))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        mockMvc.perform(post("/api/profiles/{id}/confirm", profileId)).andExpect(status().isOk());
        return profileId;
    }

    private static long id(String json) {
        return ((Number) JsonPath.read(json, "$.id")).longValue();
    }
}
