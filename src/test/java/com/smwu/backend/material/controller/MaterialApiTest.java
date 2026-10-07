package com.smwu.backend.material.controller;

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
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 시험범위 자료 업로드 → 지문 분리(Mock LLM: mock-llm/split-passages.json, 지문 2개) → 지문 수정·추가·삭제 */
@SpringBootTest
@AutoConfigureMockMvc
class MaterialApiTest {

    @Autowired
    private TestWorkspaces workspaces;

    @Autowired
    private MockMvc mockMvc;

    @Test
    void PDF를_올리면_백그라운드로_지문을_나눈다() throws Exception {
        long workspaceId = workspaces.create();
        String uploaded = mockMvc.perform(multipart("/api/workspaces/{id}/materials", workspaceId)
                        .file(new MockMultipartFile("file", "교과서 2과.pdf", "application/pdf", TestPdfs.textPdf(2))))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.title").value("교과서 2과"))
                .andExpect(jsonPath("$.sourceType").value("PDF"))
                .andExpect(jsonPath("$.status").value("SPLITTING"))
                .andExpect(jsonPath("$.pageCount").value(2))
                .andReturn().getResponse().getContentAsString();
        long materialId = id(uploaded);
        waitUntilSplit(materialId);

        mockMvc.perform(get("/api/materials/{id}", materialId))
                .andExpect(jsonPath("$.passageCount").value(2))
                .andExpect(jsonPath("$.warnings", hasSize(0)));
        mockMvc.perform(get("/api/materials/{id}/passages", materialId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].orderNo").value(1))
                .andExpect(jsonPath("$[0].title").value("Bringing New Life to Old Cities"))
                .andExpect(jsonPath("$[0].sourceLabel").value("Lesson 2 본문 1"))
                .andExpect(jsonPath("$[0].wordCount").value(54))
                .andExpect(jsonPath("$[1].content").value(org.hamcrest.Matchers.startsWith("For years, the neighborhood")));
        mockMvc.perform(get("/api/workspaces/{id}/materials", workspaceId))
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].status").value("SPLIT"));

        // 다시 나누기 → 202, 결과는 같은 지문 2개로 교체
        mockMvc.perform(post("/api/materials/{id}/split", materialId))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("SPLITTING"));
        waitUntilSplit(materialId);
        mockMvc.perform(get("/api/materials/{id}/passages", materialId)).andExpect(jsonPath("$", hasSize(2)));
    }

    @Test
    void 텍스트를_붙여넣으면_바로_지문으로_나뉘고_지문을_수정_추가_삭제할_수_있다() throws Exception {
        long workspaceId = workspaces.create();
        String created = mockMvc.perform(post("/api/workspaces/{id}/materials/text", workspaceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title": "3월 모의고사", "text": "# Passage A\\nFirst passage text.\\n---\\nSecond passage text."}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.sourceType").value("TEXT"))
                .andExpect(jsonPath("$.status").value("SPLIT"))
                .andExpect(jsonPath("$.passageCount").value(2))
                .andReturn().getResponse().getContentAsString();
        long materialId = id(created);

        String passages = mockMvc.perform(get("/api/materials/{id}/passages", materialId))
                .andExpect(jsonPath("$[0].title").value("Passage A"))
                .andExpect(jsonPath("$[0].content").value("First passage text."))
                .andReturn().getResponse().getContentAsString();
        long secondId = ((Number) JsonPath.read(passages, "$[1].id")).longValue();

        mockMvc.perform(patch("/api/passages/{id}", secondId).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\": \"Passage B\", \"content\": \"Second passage, fixed typo.\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Passage B"))
                .andExpect(jsonPath("$.content").value("Second passage, fixed typo."))
                .andExpect(jsonPath("$.edited").value(true));
        mockMvc.perform(post("/api/materials/{id}/passages", materialId).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\": \"Third passage added by hand.\", \"sourceLabel\": \"22번\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.orderNo").value(3))
                .andExpect(jsonPath("$.sourceLabel").value("22번"));
        mockMvc.perform(delete("/api/passages/{id}", secondId)).andExpect(status().isNoContent());

        mockMvc.perform(get("/api/materials/{id}/passages", materialId))
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[1].content").value("Third passage added by hand."));

        // 텍스트 자료는 다시 나눌 수 없고, 빈 본문으로 수정할 수 없다
        mockMvc.perform(post("/api/materials/{id}/split", materialId)).andExpect(status().isBadRequest());
        String remaining = mockMvc.perform(get("/api/materials/{id}/passages", materialId)).andReturn().getResponse().getContentAsString();
        mockMvc.perform(patch("/api/passages/{id}", ((Number) JsonPath.read(remaining, "$[0].id")).longValue())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"content\": \"\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 잘못된_입력과_삭제() throws Exception {
        long workspaceId = workspaces.create();
        mockMvc.perform(multipart("/api/workspaces/{id}/materials", workspaceId)
                        .file(new MockMultipartFile("file", "a.hwp", "application/octet-stream", new byte[]{1, 2, 3, 4, 5, 6})))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_FILE"));
        mockMvc.perform(post("/api/workspaces/{id}/materials/text", workspaceId).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\": \"빈 자료\", \"text\": \"   \"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/workspaces/{id}/materials/text", workspaceId).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\": \"구분선만\", \"text\": \"---\\n---\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("지문을 입력해 주세요."));

        long materialId = id(mockMvc.perform(post("/api/workspaces/{id}/materials/text", workspaceId)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"title\": \"t\", \"text\": \"Some text.\"}"))
                .andReturn().getResponse().getContentAsString());
        mockMvc.perform(delete("/api/materials/{id}", materialId)).andExpect(status().isNoContent());
        mockMvc.perform(get("/api/materials/{id}", materialId)).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/materials/{id}/passages", materialId)).andExpect(status().isNotFound());
    }

    private void waitUntilSplit(long materialId) throws Exception {
        String state = null;
        for (int i = 0; i < 100; i++) {
            Thread.sleep(100);
            state = JsonPath.read(mockMvc.perform(get("/api/materials/{id}", materialId)).andReturn().getResponse().getContentAsString(), "$.status");
            if (!"SPLITTING".equals(state)) {
                break;
            }
        }
        assertThat(state).isEqualTo("SPLIT");
    }

    private static long id(String json) {
        return ((Number) JsonPath.read(json, "$.id")).longValue();
    }
}
