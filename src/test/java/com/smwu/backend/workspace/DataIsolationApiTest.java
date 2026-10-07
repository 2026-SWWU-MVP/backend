package com.smwu.backend.workspace;

import com.jayway.jsonpath.JsonPath;
import com.smwu.backend.auth.web.CurrentUserInterceptor;
import com.smwu.backend.document.TestPdfs;
import com.smwu.backend.support.TestWorkspaces;
import com.smwu.backend.support.TestWorkspaces.Member;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 학원 간 데이터 격리 (설계서 3.7, #10).
 * A학원(기본 테스트 사용자)이 워크스페이스 하나에 기출·프로필·자료·지문·생성 작업·문제를 모두 만들어 두고,
 * B학원 원장이 그 ID로 요청하면 조회든 변경이든 전부 404가 나는지 확인한다. 같은 학원 강사는 모두 볼 수 있다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class DataIsolationApiTest {

    private static final String PASSAGES = """
            # Bringing New Life to Old Cities
            As cities age, neighborhoods can become old and lifeless, which may cause citizens to move away. When this happens, a collaboration between the local government and citizens is an effective way to revitalize the area.
            ---
            For years, the neighborhood was known for its high crime rates. However, having a limited budget, the government was unable to do so and had to come up with a new plan.
            """;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TestWorkspaces workspaces;

    /** A학원 리소스 */
    private long workspaceId;
    private long schoolId;
    private long examId;
    private long pastQuestionId;
    private long pastPassageId;
    private long profileId;
    private long materialId;
    private long passageId;
    private long jobId;
    private long problemId;
    private long worksheetId;

    @BeforeEach
    void A학원_데이터를_만든다() throws Exception {
        schoolId = workspaces.newSchool();
        workspaceId = workspaces.create(schoolId, 1);

        examId = id(mockMvc.perform(multipart("/api/workspaces/{id}/past-exams", workspaceId)
                        .file(new MockMultipartFile("file", "mid.pdf", "application/pdf", TestPdfs.textPdf(1)))
                        .param("examYear", "2025").param("semester", "1").param("examType", "MIDTERM"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        mockMvc.perform(post("/api/past-exams/{id}/analyze", examId)).andExpect(status().isAccepted());
        waitFor("/api/past-exams/" + examId, "EXTRACTED");
        String extracted = mockMvc.perform(get("/api/past-exams/{id}/questions", examId)).andReturn().getResponse().getContentAsString();
        pastQuestionId = ((Number) JsonPath.read(extracted, "$.questions[0].id")).longValue();
        pastPassageId = ((Number) JsonPath.read(extracted, "$.passages[0].id")).longValue();

        profileId = id(mockMvc.perform(post("/api/workspaces/{id}/profiles", workspaceId))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        mockMvc.perform(post("/api/profiles/{id}/confirm", profileId)).andExpect(status().isOk());

        materialId = id(mockMvc.perform(post("/api/workspaces/{id}/materials/text", workspaceId)
                        .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("title", "교과서 2과", "text", PASSAGES))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        passageId = ((Number) JsonPath.read(mockMvc.perform(get("/api/materials/{id}/passages", materialId))
                .andReturn().getResponse().getContentAsString(), "$[0].id")).longValue();

        jobId = id(mockMvc.perform(post("/api/workspaces/{id}/generation-jobs", workspaceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(generationBody(profileId, passageId)))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString());
        waitFor("/api/generation-jobs/" + jobId, "COMPLETED");
        problemId = ((Number) JsonPath.read(mockMvc.perform(get("/api/generation-jobs/{id}/problems", jobId))
                .andReturn().getResponse().getContentAsString(), "$[0].id")).longValue();
        mockMvc.perform(patch("/api/problems/{id}", problemId).contentType(MediaType.APPLICATION_JSON)
                .content("{\"reviewStatus\": \"ACCEPTED\"}")).andExpect(status().isOk());
        worksheetId = id(mockMvc.perform(post("/api/workspaces/{id}/worksheets", workspaceId).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\": \"시험지\", \"problemIds\": [" + problemId + "]}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
    }

    @Test
    void 다른_학원_원장이_A학원_리소스를_요청하면_전부_404() throws Exception {
        Member other = workspaces.newAcademy();
        long otherWorkspace = workspaces.create(other, schoolId, 1); // 같은 학교·학년이어도 학원이 다르면 별개

        for (Map.Entry<String, AbstractMockHttpServletRequestBuilder<?>> request : requestsOnAcademyA(otherWorkspace).entrySet()) {
            int status = mockMvc.perform(request.getValue().header(CurrentUserInterceptor.HEADER, other.userId()))
                    .andReturn().getResponse().getStatus();
            assertThat(status).as(request.getKey()).isEqualTo(404);
        }

        // 목록에도 보이지 않는다
        mockMvc.perform(get("/api/workspaces").header(CurrentUserInterceptor.HEADER, other.userId()))
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[*].id", not(hasItem((int) workspaceId))));

        // B학원의 요청은 아무것도 바꾸지 못했다
        mockMvc.perform(get("/api/past-exams/{id}", examId)).andExpect(status().isOk());
        mockMvc.perform(get("/api/materials/{id}/passages", materialId)).andExpect(jsonPath("$", hasSize(2)));
        mockMvc.perform(get("/api/workspaces/{id}/profiles", workspaceId))
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].status").value("CONFIRMED"));
        mockMvc.perform(get("/api/workspaces/{id}/generation-jobs", workspaceId)).andExpect(jsonPath("$", hasSize(1)));
        mockMvc.perform(get("/api/workspaces/{id}/materials", otherWorkspace)
                        .header(CurrentUserInterceptor.HEADER, other.userId()))
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void 같은_학원_강사는_원장이_만든_리소스를_모두_본다() throws Exception {
        long teacherId = workspaces.newTeacher();
        List<String> reads = List.of(
                "/api/workspaces/" + workspaceId,
                "/api/workspaces/" + workspaceId + "/past-exams",
                "/api/past-exams/" + examId,
                "/api/past-exams/" + examId + "/questions",
                "/api/workspaces/" + workspaceId + "/profiles",
                "/api/workspaces/" + workspaceId + "/profiles/confirmed",
                "/api/profiles/" + profileId,
                "/api/workspaces/" + workspaceId + "/materials",
                "/api/materials/" + materialId,
                "/api/materials/" + materialId + "/passages",
                "/api/workspaces/" + workspaceId + "/generation-jobs",
                "/api/generation-jobs/" + jobId,
                "/api/generation-jobs/" + jobId + "/problems",
                "/api/problems/" + problemId,
                "/api/workspaces/" + workspaceId + "/school-trends");
        for (String url : reads) {
            int status = mockMvc.perform(get(url).header(CurrentUserInterceptor.HEADER, teacherId)).andReturn().getResponse().getStatus();
            assertThat(status).as(url).isEqualTo(200);
        }
        mockMvc.perform(get("/api/workspaces").header(CurrentUserInterceptor.HEADER, teacherId))
                .andExpect(jsonPath("$[*].id", hasItem((int) workspaceId)));
    }

    @Test
    void 학원_소속_전_사용자는_403_NO_ACADEMY() throws Exception {
        long userId = workspaces.newUserWithoutAcademy();
        List<AbstractMockHttpServletRequestBuilder<?>> requests = List.of(
                get("/api/workspaces"),
                get("/api/workspaces/{id}", workspaceId),
                get("/api/workspaces/{id}/past-exams", workspaceId),
                get("/api/past-exams/{id}", examId),
                get("/api/profiles/{id}", profileId),
                get("/api/materials/{id}", materialId),
                get("/api/generation-jobs/{id}", jobId),
                get("/api/problems/{id}", problemId),
                get("/api/academy"));
        for (AbstractMockHttpServletRequestBuilder<?> request : requests) {
            mockMvc.perform(request.header(CurrentUserInterceptor.HEADER, userId))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("NO_ACADEMY"));
        }
    }

    /** A학원 워크스페이스 하위 API 전체. 요청 본문은 검증(400)에 걸리지 않게 올바른 값으로 넣는다 */
    private Map<String, AbstractMockHttpServletRequestBuilder<?>> requestsOnAcademyA(long otherWorkspace) {
        Map<String, AbstractMockHttpServletRequestBuilder<?>> r = new LinkedHashMap<>();
        // 워크스페이스
        r.put("워크스페이스 상세", get("/api/workspaces/{id}", workspaceId));
        r.put("워크스페이스 삭제", delete("/api/workspaces/{id}", workspaceId));
        r.put("학교 DB 경향", get("/api/workspaces/{id}/school-trends", workspaceId));
        r.put("학교 DB 경향 요약", get("/api/workspaces/{id}/school-trends/summary", workspaceId));
        // 기출
        r.put("기출 업로드", multipart("/api/workspaces/{id}/past-exams", workspaceId)
                .file(new MockMultipartFile("file", "mid.pdf", "application/pdf", TestPdfs.textPdf(1)))
                .param("examYear", "2024").param("semester", "2").param("examType", "FINAL"));
        r.put("기출 목록", get("/api/workspaces/{id}/past-exams", workspaceId));
        r.put("기출 상세", get("/api/past-exams/{id}", examId));
        r.put("기출 추출 결과", get("/api/past-exams/{id}/questions", examId));
        r.put("기출 추출 시작", post("/api/past-exams/{id}/analyze", examId));
        r.put("기출 문항 수정", patch("/api/past-questions/{id}", pastQuestionId)
                .contentType(MediaType.APPLICATION_JSON).content("{\"stem\": \"수정한 발문\"}"));
        r.put("기출 지문 수정", patch("/api/past-passages/{id}", pastPassageId)
                .contentType(MediaType.APPLICATION_JSON).content("{\"text\": \"수정한 지문\"}"));
        r.put("기출 삭제", delete("/api/past-exams/{id}", examId));
        // 출제 프로필
        r.put("프로필 생성", post("/api/workspaces/{id}/profiles", workspaceId));
        r.put("프로필 목록", get("/api/workspaces/{id}/profiles", workspaceId));
        r.put("확정 프로필", get("/api/workspaces/{id}/profiles/confirmed", workspaceId));
        r.put("프로필 상세", get("/api/profiles/{id}", profileId));
        r.put("프로필 확정", post("/api/profiles/{id}/confirm", profileId));
        r.put("프로필 AI 재검토", post("/api/profiles/{id}/recheck", profileId));
        r.put("프로필 강사 의견", post("/api/profiles/{id}/feedback", profileId)
                .contentType(MediaType.APPLICATION_JSON).content("{\"text\": \"이번엔 어구 배열 위주\"}"));
        r.put("프로필 직접 수정", patch("/api/profiles/{id}", profileId)
                .contentType(MediaType.APPLICATION_JSON).content("{\"typeMixPerPassage\": {\"SENTENCE_ORDER\": 2}}"));
        // 시험범위 자료·지문
        r.put("자료 PDF 업로드", multipart("/api/workspaces/{id}/materials", workspaceId)
                .file(new MockMultipartFile("file", "lesson.pdf", "application/pdf", TestPdfs.textPdf(1))));
        r.put("자료 텍스트 붙여넣기", post("/api/workspaces/{id}/materials/text", workspaceId)
                .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("title", "교과서 3과", "text", PASSAGES))));
        r.put("자료 목록", get("/api/workspaces/{id}/materials", workspaceId));
        r.put("자료 상세", get("/api/materials/{id}", materialId));
        r.put("자료 다시 나누기", post("/api/materials/{id}/split", materialId));
        r.put("지문 목록", get("/api/materials/{id}/passages", materialId));
        r.put("지문 추가", post("/api/materials/{id}/passages", materialId)
                .contentType(MediaType.APPLICATION_JSON).content("{\"content\": \"A new passage.\"}"));
        r.put("지문 수정", patch("/api/passages/{id}", passageId)
                .contentType(MediaType.APPLICATION_JSON).content("{\"content\": \"Edited passage.\"}"));
        r.put("지문 삭제", delete("/api/passages/{id}", passageId));
        r.put("자료 삭제", delete("/api/materials/{id}", materialId));
        // 문제 생성
        r.put("생성 작업 시작", post("/api/workspaces/{id}/generation-jobs", workspaceId)
                .contentType(MediaType.APPLICATION_JSON).content(generationBody(profileId, passageId)));
        r.put("내 워크스페이스에 A학원 프로필·지문으로 생성", post("/api/workspaces/{id}/generation-jobs", otherWorkspace)
                .contentType(MediaType.APPLICATION_JSON).content(generationBody(profileId, passageId)));
        r.put("생성 작업 목록", get("/api/workspaces/{id}/generation-jobs", workspaceId));
        r.put("생성 작업 진행률", get("/api/generation-jobs/{id}", jobId));
        r.put("생성 문항 목록", get("/api/generation-jobs/{id}/problems", jobId));
        r.put("문항 상세", get("/api/problems/{id}", problemId));
        r.put("문항 재생성", post("/api/problems/{id}/regenerate", problemId));
        r.put("문항 검수", patch("/api/problems/{id}", problemId).contentType(MediaType.APPLICATION_JSON)
                .content("{\"reviewStatus\": \"REJECTED\"}"));
        r.put("워크스페이스 문항 목록", get("/api/workspaces/{id}/problems", workspaceId));
        r.put("검수 통계", get("/api/workspaces/{id}/review-stats", workspaceId));
        r.put("시험지 만들기", post("/api/workspaces/{id}/worksheets", workspaceId).contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\": \"x\", \"problemIds\": [" + problemId + "]}"));
        r.put("내 워크스페이스에 남의 문항으로 시험지", post("/api/workspaces/{id}/worksheets", otherWorkspace)
                .contentType(MediaType.APPLICATION_JSON).content("{\"title\": \"x\", \"problemIds\": [" + problemId + "]}"));
        r.put("시험지 목록", get("/api/workspaces/{id}/worksheets", workspaceId));
        r.put("시험지 상세", get("/api/worksheets/{id}", worksheetId));
        r.put("시험지 수정", patch("/api/worksheets/{id}", worksheetId).contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\": \"x\"}"));
        r.put("시험지 삭제", delete("/api/worksheets/{id}", worksheetId));
        return r;
    }

    private static String generationBody(long profileId, long passageId) {
        return """
                {"profileId": %d, "passageIds": [%d], "perPassage": [{"type": "SUMMARY_BLANK", "count": 1, "options": {"blankCount": 2}}]}
                """.formatted(profileId, passageId);
    }

    private void waitFor(String url, String expected) throws Exception {
        String status = null;
        for (int i = 0; i < 150 && !expected.equals(status); i++) {
            Thread.sleep(100);
            status = JsonPath.read(mockMvc.perform(get(url)).andReturn().getResponse().getContentAsString(), "$.status");
        }
        assertThat(status).as(url).isEqualTo(expected);
    }

    private static String json(Object value) {
        return new tools.jackson.databind.json.JsonMapper().writeValueAsString(value);
    }

    private static long id(String json) {
        return ((Number) JsonPath.read(json, "$.id")).longValue();
    }
}
