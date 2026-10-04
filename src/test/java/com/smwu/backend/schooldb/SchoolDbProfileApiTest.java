package com.smwu.backend.schooldb;

import com.jayway.jsonpath.JsonPath;
import com.smwu.backend.document.TestPdfs;
import com.smwu.backend.workspace.domain.Workspace;
import com.smwu.backend.workspace.repository.WorkspaceRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 출제 프로필이 학교 DB 경향을 쓰는지 (#41, Mock LLM) */
@SpringBootTest
@AutoConfigureMockMvc
class SchoolDbProfileApiTest {

    private static final String PASSAGE = """
            # Bringing New Life to Old Cities
            As cities age, neighborhoods can become old and lifeless, which may cause citizens to move away. When this happens, a collaboration between the local government and citizens is an effective way to revitalize the area.
            """;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private WorkspaceRepository workspaceRepository;

    @Test
    void 기출이_없는_신규_학원도_학교_DB로_프로필을_만들고_문제를_생성한다() throws Exception {
        long schoolId = school("신규학원지원고등학교");
        long other = workspaceRepository.save(new Workspace(2L, schoolId, 1, null)).getId();
        extractedExam(other, 2025, 1, "MIDTERM");
        long mine = workspace(schoolId, 1);

        String profile = mockMvc.perform(post("/api/workspaces/{id}/profiles", mine))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.stats.examCount").value(1))
                .andExpect(jsonPath("$.stats.schoolDbExamCount").value(1))
                .andExpect(jsonPath("$.rules", hasSize(3)))
                .andExpect(jsonPath("$.rules[*].source", everyItem(is("SCHOOL_DB"))))
                .andExpect(jsonPath("$.changeSummary[0]", startsWith("우리 학원 기출 없이 학교 DB 기출 1회분 · 학원 1곳 기준")))
                .andExpect(jsonPath("$.typeMixPerPassage.SUMMARY_BLANK").value(2))
                .andReturn().getResponse().getContentAsString();
        long profileId = id(profile);
        mockMvc.perform(post("/api/profiles/{id}/confirm", profileId)).andExpect(status().isOk());

        long material = id(mockMvc.perform(post("/api/workspaces/{id}/materials/text", mine)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JsonMapper.builder().build().writeValueAsString(Map.of("title", "2과", "text", PASSAGE))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        List<Number> passageIds = JsonPath.read(mockMvc.perform(get("/api/materials/{id}/passages", material))
                .andReturn().getResponse().getContentAsString(), "$[*].id");
        long job = id(mockMvc.perform(post("/api/workspaces/{id}/generation-jobs", mine).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"profileId\": %d, \"passageIds\": [%d], \"perPassage\": [{\"type\": \"SUMMARY_BLANK\", \"count\": 1}]}"
                                .formatted(profileId, passageIds.get(0).longValue())))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString());
        String status = null;
        for (int i = 0; i < 100 && !"COMPLETED".equals(status); i++) {
            Thread.sleep(50);
            status = JsonPath.read(mockMvc.perform(get("/api/generation-jobs/{id}", job)).andReturn().getResponse().getContentAsString(),
                    "$.status");
        }
        assertThat(status).isEqualTo("COMPLETED");
        mockMvc.perform(get("/api/generation-jobs/{id}/problems", job))
                .andExpect(jsonPath("$[0].validationStatus").value("PASSED"));
    }

    @Test
    void 내_기출과_같은_회차는_한_번만_세고_다른_회차만_더한다() throws Exception {
        long schoolId = school("회차합치기고등학교");
        long other = workspaceRepository.save(new Workspace(2L, schoolId, 2, null)).getId();
        extractedExam(other, 2025, 1, "MIDTERM");
        extractedExam(other, 2024, 2, "FINAL");
        long mine = workspace(schoolId, 2);
        extractedExam(mine, 2025, 1, "MIDTERM");

        mockMvc.perform(post("/api/workspaces/{id}/profiles", mine))
                .andExpect(status().isCreated())
                // 내 기출 1개 + 다른 회차 1개 (2025 1학기 중간은 내 것과 같은 회차라 제외)
                .andExpect(jsonPath("$.stats.examCount").value(2))
                .andExpect(jsonPath("$.stats.schoolDbExamCount").value(1))
                .andExpect(jsonPath("$.stats.totalQuestions").value(6))
                .andExpect(jsonPath("$.sourceExams", hasSize(1)))
                .andExpect(jsonPath("$.rules[*].source", hasItem("PAST_EXAM")))
                .andExpect(jsonPath("$.changeSummary[1]").value(
                        "학교 DB 기출 1회분 · 학원 1곳(다른 학원 기출, 우리 기출과 같은 회차 제외)의 경향을 함께 반영했습니다."));
    }

    @Test
    void 내_기출도_학교_DB도_없으면_409() throws Exception {
        long mine = workspace(school("빈학교고등학교"), 3);
        mockMvc.perform(post("/api/workspaces/{id}/profiles", mine))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("NO_EXTRACTED_PAST_EXAM"));
    }

    private long workspace(long schoolId, int grade) throws Exception {
        return id(mockMvc.perform(post("/api/workspaces").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"schoolId\": %d, \"grade\": %d}".formatted(schoolId, grade)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
    }

    private long school(String name) throws Exception {
        return id(mockMvc.perform(post("/api/schools").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"%s\"}".formatted(name)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
    }

    private void extractedExam(long workspaceId, int year, int semester, String examType) throws Exception {
        long examId = id(mockMvc.perform(multipart("/api/workspaces/{id}/past-exams", workspaceId)
                        .file(new MockMultipartFile("file", "exam.pdf", "application/pdf", TestPdfs.textPdf(1)))
                        .param("examYear", String.valueOf(year)).param("semester", String.valueOf(semester))
                        .param("examType", examType))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        mockMvc.perform(post("/api/past-exams/{id}/analyze", examId)).andExpect(status().isAccepted());
        String state = null;
        for (int i = 0; i < 100 && !"EXTRACTED".equals(state); i++) {
            Thread.sleep(50);
            state = JsonPath.read(mockMvc.perform(get("/api/past-exams/{id}", examId)).andReturn().getResponse().getContentAsString(),
                    "$.status");
        }
        assertThat(state).isEqualTo("EXTRACTED");
    }

    private static long id(String json) {
        return ((Number) JsonPath.read(json, "$.id")).longValue();
    }
}
