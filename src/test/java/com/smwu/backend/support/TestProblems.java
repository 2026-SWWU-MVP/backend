package com.smwu.backend.support;

import com.jayway.jsonpath.JsonPath;
import com.smwu.backend.document.TestPdfs;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 검수·시험지·PDF 테스트 준비 (Mock LLM): 확정 프로필 + 지문 2개 × (요약문 빈칸, 어구 배열) = 4문항.
 * Mock 문제 응답은 지문 A(요약문 빈칸), 지문 B(어구 배열)에 맞는다.
 */
public final class TestProblems {

    public static final String PASSAGES = """
            # Bringing New Life to Old Cities
            As cities age, neighborhoods can become old and lifeless, which may cause citizens to move away. When this happens, a collaboration between the local government and citizens is an effective way to revitalize the area.
            ---
            # Crime and Budget
            For years, the neighborhood was known for its high crime rates. However, having a limited budget, the government was unable to do so and had to come up with a new plan.
            """;

    private TestProblems() {
    }

    /** @param problemIds [A 요약문 빈칸, A 어구 배열, B 요약문 빈칸, B 어구 배열] */
    public record Generated(long workspaceId, List<Long> problemIds) {
    }

    public static Generated generate(MockMvc mockMvc, TestWorkspaces workspaces) throws Exception {
        long workspaceId = workspaces.create();
        long profileId = confirmedProfile(mockMvc, workspaceId);
        String material = mockMvc.perform(post("/api/workspaces/{id}/materials/text", workspaceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JsonMapper.builder().build().writeValueAsString(Map.of("title", "교과서 2과", "text", PASSAGES))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        List<Number> passageIds = JsonPath.read(mockMvc.perform(get("/api/materials/{id}/passages", id(material)))
                .andReturn().getResponse().getContentAsString(), "$[*].id");
        long jobId = id(mockMvc.perform(post("/api/workspaces/{id}/generation-jobs", workspaceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"profileId": %d, "passageIds": [%d, %d],
                                 "perPassage": [{"type": "SUMMARY_BLANK", "count": 1}, {"type": "SENTENCE_ORDER", "count": 1}]}
                                """.formatted(profileId, passageIds.get(0).longValue(), passageIds.get(1).longValue())))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString());
        String state = null;
        for (int i = 0; i < 150 && !"COMPLETED".equals(state); i++) {
            Thread.sleep(50);
            state = JsonPath.read(mockMvc.perform(get("/api/generation-jobs/{id}", jobId)).andReturn().getResponse().getContentAsString(),
                    "$.status");
        }
        assertThat(state).isEqualTo("COMPLETED");
        List<Number> problems = JsonPath.read(mockMvc.perform(get("/api/generation-jobs/{id}/problems", jobId))
                .andReturn().getResponse().getContentAsString(), "$[*].id");
        return new Generated(workspaceId, problems.stream().map(Number::longValue).toList());
    }

    public static void accept(MockMvc mockMvc, long problemId) throws Exception {
        mockMvc.perform(patch("/api/problems/{id}", problemId).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reviewStatus\": \"ACCEPTED\"}"))
                .andExpect(status().isOk());
    }

    public static long confirmedProfile(MockMvc mockMvc, long workspaceId) throws Exception {
        long examId = id(mockMvc.perform(multipart("/api/workspaces/{id}/past-exams", workspaceId)
                        .file(new MockMultipartFile("file", "mid.pdf", "application/pdf", TestPdfs.textPdf(1)))
                        .param("examYear", "2025").param("semester", "1").param("examType", "MIDTERM"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        mockMvc.perform(post("/api/past-exams/{id}/analyze", examId)).andExpect(status().isAccepted());
        String state = null;
        for (int i = 0; i < 100 && !"EXTRACTED".equals(state); i++) {
            Thread.sleep(50);
            state = JsonPath.read(mockMvc.perform(get("/api/past-exams/{id}", examId)).andReturn().getResponse().getContentAsString(),
                    "$.status");
        }
        long profileId = id(mockMvc.perform(post("/api/workspaces/{id}/profiles", workspaceId))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        mockMvc.perform(post("/api/profiles/{id}/confirm", profileId)).andExpect(status().isOk());
        return profileId;
    }

    public static long id(String json) {
        return ((Number) JsonPath.read(json, "$.id")).longValue();
    }
}
