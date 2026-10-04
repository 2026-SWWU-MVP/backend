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

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 학교 DB: 학원 기출 → 회차 기여 (Mock LLM 추출) */
@SpringBootTest
@AutoConfigureMockMvc
class SchoolDbApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private WorkspaceRepository workspaceRepository;

    @Test
    void 두_학원이_같은_회차를_올리면_회차는_하나_기여_학원은_둘() throws Exception {
        long schoolId = school("학교디비시험고등학교");
        long mine = id(mockMvc.perform(post("/api/workspaces").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"schoolId\": %d, \"grade\": 1}".formatted(schoolId)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        // 다른 학원의 같은 학교·학년 워크스페이스 (회원 기능 전이라 저장소로 직접 만든다)
        long other = workspaceRepository.save(new Workspace(2L, schoolId, 1, null)).getId();

        long myExam = extractedExam(mine, 2025, 1, "MIDTERM");
        String rounds = mockMvc.perform(get("/api/schools/{id}/exams", schoolId).param("grade", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].examYear").value(2025))
                .andExpect(jsonPath("$[0].contributorCount").value(1))
                .andExpect(jsonPath("$[0].stats.totalQuestions").value(3))
                .andExpect(jsonPath("$[0].stats.passageCount").value(2))
                .andReturn().getResponse().getContentAsString();
        // 원문(지문·발문)과 학원 정보는 나가지 않는다
        assertThat(rounds).doesNotContain("lifeless", "Old Cities", "윗글", "academy", "pastExamId");

        long otherExam = extractedExam(other, 2025, 1, "MIDTERM");
        mockMvc.perform(get("/api/schools/{id}/exams", schoolId).param("grade", "1"))
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].contributorCount").value(2));

        // 다른 회차는 따로, 최신순
        long older = extractedExam(mine, 2024, 2, "FINAL");
        mockMvc.perform(get("/api/schools/{id}/exams", schoolId).param("grade", "1"))
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].examYear").value(2025))
                .andExpect(jsonPath("$[1].examYear").value(2024))
                .andExpect(jsonPath("$[1].examType").value("FINAL"));
        mockMvc.perform(get("/api/schools/{id}/exams", schoolId).param("grade", "2"))
                .andExpect(jsonPath("$", hasSize(0)));

        // 삭제하면 기여가 빠지고, 기여가 없는 회차는 사라진다
        mockMvc.perform(delete("/api/past-exams/{id}", otherExam)).andExpect(status().isNoContent());
        mockMvc.perform(delete("/api/past-exams/{id}", older)).andExpect(status().isNoContent());
        mockMvc.perform(get("/api/schools/{id}/exams", schoolId).param("grade", "1"))
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].contributorCount").value(1));
        assertThat(myExam).isPositive();
    }

    @Test
    void 강사가_문항을_고치면_회차_통계도_바뀐다() throws Exception {
        long schoolId = school("문항수정반영고등학교");
        long workspace = id(mockMvc.perform(post("/api/workspaces").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"schoolId\": %d, \"grade\": 3}".formatted(schoolId)))
                .andReturn().getResponse().getContentAsString());
        long exam = extractedExam(workspace, 2025, 2, "FINAL");
        String before = mockMvc.perform(get("/api/schools/{id}/exams", schoolId).param("grade", "3"))
                .andReturn().getResponse().getContentAsString();
        int summaryBefore = JsonPath.read(before, "$[0].stats.typeCounts.SUMMARY_BLANK");

        String questions = mockMvc.perform(get("/api/past-exams/{id}/questions", exam)).andReturn().getResponse().getContentAsString();
        int firstNotSummary = ((Number) JsonPath.<java.util.List<Object>>read(questions,
                "$.questions[?(@.type != 'SUMMARY_BLANK')].id").get(0)).intValue();
        mockMvc.perform(patch("/api/past-questions/{id}", firstNotSummary).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\": \"SUMMARY_BLANK\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/schools/{id}/exams", schoolId).param("grade", "3"))
                .andExpect(jsonPath("$[0].stats.typeCounts.SUMMARY_BLANK").value(summaryBefore + 1));
    }

    @Test
    void 학교_경향을_학교와_워크스페이스로_조회한다() throws Exception {
        long schoolId = school("경향조회고등학교");
        long workspace = id(mockMvc.perform(post("/api/workspaces").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"schoolId\": %d, \"grade\": 2}".formatted(schoolId)))
                .andReturn().getResponse().getContentAsString());

        mockMvc.perform(get("/api/workspaces/{id}/school-trends", workspace))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.examCount").value(0))
                .andExpect(jsonPath("$.confidence").value("NONE"))
                .andExpect(jsonPath("$.basis").value("아직 학교 DB에 기출이 없습니다."));

        extractedExam(workspace, 2025, 1, "MIDTERM");
        workspaceRepository.save(new Workspace(3L, schoolId, 2, null));
        String trend = mockMvc.perform(get("/api/schools/{id}/trends", schoolId).param("grade", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.schoolName").value("경향조회고등학교"))
                .andExpect(jsonPath("$.examCount").value(1))
                .andExpect(jsonPath("$.confidence").value("LOW"))
                .andExpect(jsonPath("$.basis").value("학교 DB 기출 1회분 · 학원 1곳 기준"))
                .andExpect(jsonPath("$.latestExam").value("2025년 1학기 중간"))
                .andExpect(jsonPath("$.types[0].label").isNotEmpty())
                .andExpect(jsonPath("$.byYear[0].year").value(2025))
                .andReturn().getResponse().getContentAsString();
        assertThat(trend).doesNotContain("lifeless", "윗글");

        mockMvc.perform(get("/api/schools/{id}/trends", schoolId).param("grade", "5")).andExpect(status().isBadRequest());
    }

    @Test
    void 경향_요약은_회차_구성이_바뀔_때만_새로_만든다() throws Exception {
        long schoolId = school("경향요약고등학교");
        long workspace = id(mockMvc.perform(post("/api/workspaces").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"schoolId\": %d, \"grade\": 1}".formatted(schoolId)))
                .andReturn().getResponse().getContentAsString());

        mockMvc.perform(get("/api/workspaces/{id}/school-trends/summary", workspace))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.headline").doesNotExist())
                .andExpect(jsonPath("$.basis").value("아직 학교 DB에 기출이 없습니다."));

        extractedExam(workspace, 2025, 1, "MIDTERM");
        mockMvc.perform(get("/api/schools/{id}/trends/summary", schoolId).param("grade", "1"))
                .andExpect(jsonPath("$.cached").value(false))
                .andExpect(jsonPath("$.examCount").value(1))
                .andExpect(jsonPath("$.headline").isNotEmpty())
                .andExpect(jsonPath("$.points", hasSize(3)))
                .andExpect(jsonPath("$.llmModel").value("mock"));
        mockMvc.perform(get("/api/workspaces/{id}/school-trends/summary", workspace))
                .andExpect(jsonPath("$.cached").value(true));

        extractedExam(workspace, 2024, 2, "FINAL");
        mockMvc.perform(get("/api/schools/{id}/trends/summary", schoolId).param("grade", "1"))
                .andExpect(jsonPath("$.cached").value(false))
                .andExpect(jsonPath("$.examCount").value(2));
    }

    @Test
    void 워크스페이스가_없는_기출은_기여하지_않는다() throws Exception {
        long schoolId = school("기여없음고등학교");
        extractedExam(987654L, 2025, 1, "MIDTERM");
        mockMvc.perform(get("/api/schools/{id}/exams", schoolId).param("grade", "1"))
                .andExpect(jsonPath("$", hasSize(0)));
        mockMvc.perform(get("/api/schools/{id}/exams", 99999999).param("grade", "1"))
                .andExpect(status().isNotFound());
    }

    private long school(String name) throws Exception {
        return id(mockMvc.perform(post("/api/schools").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"%s\"}".formatted(name)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
    }

    private long extractedExam(long workspaceId, int year, int semester, String examType) throws Exception {
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
        return examId;
    }

    private static long id(String json) {
        return ((Number) JsonPath.read(json, "$.id")).longValue();
    }
}
