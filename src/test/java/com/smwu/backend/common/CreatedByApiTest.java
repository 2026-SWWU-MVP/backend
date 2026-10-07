package com.smwu.backend.common;

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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 누가 올리고 만들고 확정했는지 기록·표시 (설계서 3.4) */
@SpringBootTest
@AutoConfigureMockMvc
class CreatedByApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TestWorkspaces workspaces;

    @Test
    void 기출_자료_프로필_의견_확정에_작성자가_남는다() throws Exception {
        long workspaceId = workspaces.create();
        long teacher = workspaces.newTeacher();

        // 강사가 기출 업로드 → 원장이 목록에서 올린 사람을 본다
        long examId = id(mockMvc.perform(multipart("/api/workspaces/{id}/past-exams", workspaceId)
                        .file(new MockMultipartFile("file", "mid.pdf", "application/pdf", TestPdfs.textPdf(1)))
                        .param("examYear", "2025").param("semester", "1").param("examType", "MIDTERM")
                        .header("X-User-Id", teacher))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.createdBy").value(teacher))
                .andExpect(jsonPath("$.createdByName").isNotEmpty())
                .andReturn().getResponse().getContentAsString());
        mockMvc.perform(get("/api/workspaces/{id}/past-exams", workspaceId))
                .andExpect(jsonPath("$[0].createdBy").value(teacher));

        mockMvc.perform(post("/api/workspaces/{id}/materials/text", workspaceId).header("X-User-Id", teacher)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\": \"2과\", \"text\": \"As cities age, neighborhoods can become old and lifeless.\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.createdBy").value(teacher));

        mockMvc.perform(post("/api/past-exams/{id}/analyze", examId)).andExpect(status().isAccepted());
        String state = null;
        for (int i = 0; i < 100 && !"EXTRACTED".equals(state); i++) {
            Thread.sleep(50);
            state = JsonPath.read(mockMvc.perform(get("/api/past-exams/{id}", examId)).andReturn().getResponse().getContentAsString(),
                    "$.status");
        }

        // 원장이 프로필 생성 → 강사가 의견 → 강사가 확정
        long v1 = id(mockMvc.perform(post("/api/workspaces/{id}/profiles", workspaceId))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.createdByName").value("테스트원장"))
                .andReturn().getResponse().getContentAsString());
        String v2 = mockMvc.perform(post("/api/profiles/{id}/feedback", v1).header("X-User-Id", teacher)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\": \"이번엔 어구 배열 위주래요\", \"persistent\": false}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.createdBy").value(teacher))
                .andExpect(jsonPath("$.teacherNotes[0].createdBy").value(teacher))
                .andExpect(jsonPath("$.teacherNotes[0].createdByName").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
        mockMvc.perform(post("/api/profiles/{id}/confirm", id(v2)).header("X-User-Id", teacher))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.confirmedBy").value(teacher))
                .andExpect(jsonPath("$.confirmedByName").isNotEmpty());
        assertThat(examId).isPositive();
    }

    private static long id(String json) {
        return ((Number) JsonPath.read(json, "$.id")).longValue();
    }
}
