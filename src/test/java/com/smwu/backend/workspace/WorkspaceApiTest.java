package com.smwu.backend.workspace;

import com.jayway.jsonpath.JsonPath;
import com.smwu.backend.document.TestPdfs;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class WorkspaceApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void 시드_학교를_이름과_별칭으로_검색한다() throws Exception {
        mockMvc.perform(get("/api/schools").param("query", "건대 부고"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("건국대학교사범대학부속고등학교"))
                .andExpect(jsonPath("$[0].region").value("서울 광진구"))
                .andExpect(jsonPath("$[0].aliases", hasItem("건대부고")));
        mockMvc.perform(get("/api/schools").param("query", "압구정"))
                .andExpect(jsonPath("$[0].name").value("압구정고등학교"));
        mockMvc.perform(get("/api/schools").param("query", " "))
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void 학교를_등록하고_이름이나_별칭이_겹치면_409() throws Exception {
        mockMvc.perform(post("/api/schools").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"테스트등록고등학교\", \"region\": \"서울 송파구\", \"aliases\": [\"테등고\"]}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.aliases[0]").value("테등고"));

        mockMvc.perform(post("/api/schools").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"테스트 등록고등학교\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SCHOOL_DUPLICATED"));
        mockMvc.perform(post("/api/schools").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"다른이름고등학교\", \"aliases\": [\"테등고\"]}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("이미 등록된 학교입니다: 테스트등록고등학교 (서울 송파구)"));
        mockMvc.perform(post("/api/schools").contentType(MediaType.APPLICATION_JSON).content("{\"name\": \"\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 워크스페이스_생성_조회_중복_삭제() throws Exception {
        long schoolId = id(mockMvc.perform(post("/api/schools").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"워크스페이스시험고등학교\", \"aliases\": [\"워시고\"]}"))
                .andReturn().getResponse().getContentAsString());

        long workspaceId = id(mockMvc.perform(post("/api/workspaces").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"schoolId\": %d, \"grade\": 2}".formatted(schoolId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.title").value("워시고 2학년"))
                .andExpect(jsonPath("$.school.name").value("워크스페이스시험고등학교"))
                .andReturn().getResponse().getContentAsString());

        mockMvc.perform(post("/api/workspaces").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"schoolId\": %d, \"grade\": 2}".formatted(schoolId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("WORKSPACE_DUPLICATED"));
        mockMvc.perform(post("/api/workspaces").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"schoolId\": %d, \"grade\": 4}".formatted(schoolId)))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/workspaces").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"schoolId\": 99999999, \"grade\": 1}"))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/api/workspaces"))
                .andExpect(jsonPath("$[*].id", hasItem((int) workspaceId)));
        mockMvc.perform(get("/api/workspaces/{id}", workspaceId))
                .andExpect(jsonPath("$.grade").value(2));

        mockMvc.perform(delete("/api/workspaces/{id}", workspaceId)).andExpect(status().isNoContent());
        mockMvc.perform(get("/api/workspaces/{id}", workspaceId)).andExpect(status().isNotFound());
    }

    @Test
    void 기출이_있는_워크스페이스는_삭제할_수_없다() throws Exception {
        long schoolId = id(mockMvc.perform(post("/api/schools").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"삭제불가고등학교\"}"))
                .andReturn().getResponse().getContentAsString());
        long workspaceId = id(mockMvc.perform(post("/api/workspaces").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"schoolId\": %d, \"grade\": 1}".formatted(schoolId)))
                .andReturn().getResponse().getContentAsString());
        mockMvc.perform(multipart("/api/workspaces/{id}/past-exams", workspaceId)
                        .file(new MockMultipartFile("file", "mid.pdf", "application/pdf", TestPdfs.textPdf(1)))
                        .param("examYear", "2025").param("semester", "1").param("examType", "MIDTERM"))
                .andExpect(status().isCreated());

        mockMvc.perform(delete("/api/workspaces/{id}", workspaceId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("WORKSPACE_NOT_EMPTY"));
    }

    private static long id(String json) {
        return ((Number) JsonPath.read(json, "$.id")).longValue();
    }
}
