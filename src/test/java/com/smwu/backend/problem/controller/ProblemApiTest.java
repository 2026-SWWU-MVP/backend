package com.smwu.backend.problem.controller;

import com.jayway.jsonpath.JsonPath;
import com.smwu.backend.document.TestPdfs;
import com.smwu.backend.pastexam.extraction.QuestionType;
import com.smwu.backend.problem.domain.Problem;
import com.smwu.backend.problem.domain.ValidationReport;
import com.smwu.backend.problem.domain.ValidationStatus;
import com.smwu.backend.problem.repository.ProblemRepository;
import com.smwu.backend.problem.service.ProblemGenerationService;
import com.smwu.backend.problem.type.PassageSource;
import com.smwu.backend.problem.type.ProblemOptions;
import com.smwu.backend.problem.type.ValidationCheck;
import com.smwu.backend.support.TestWorkspaces;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 생성 문항 조회·개별 재생성 API (Mock LLM: 생성·블라인드 풀이 모두 일치 → PASSED) */
@SpringBootTest
@AutoConfigureMockMvc
class ProblemApiTest {

    @Autowired
    private TestWorkspaces workspaces;
    private static final PassageSource PASSAGE = new PassageSource(null, "Bringing New Life to Old Cities",
            "As cities age, neighborhoods can become old and lifeless, which may cause citizens to move away. "
                    + "When this happens, a collaboration between the local government and citizens is an effective way "
                    + "to revitalize the area.");

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ProblemGenerationService generationService;
    @Autowired
    private ProblemRepository problemRepository;

    @Test
    void 생성된_문항을_조회하고_다시_생성하면_같은_ID로_덮어쓴다() throws Exception {
        long profileId = confirmedProfile(workspaces.create());
        Problem problem = generationService.generate(profileId, PASSAGE, QuestionType.SUMMARY_BLANK, null, 1L);

        mockMvc.perform(get("/api/problems/{id}", problem.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("SUMMARY_BLANK"))
                .andExpect(jsonPath("$.typeLabel").value("요약문 빈칸"))
                .andExpect(jsonPath("$.validationStatus").value("PASSED"))
                .andExpect(jsonPath("$.validationIssues", hasSize(0)))
                .andExpect(jsonPath("$.answer.blanks[1]").value("revitalize"))
                .andExpect(jsonPath("$.passageTitle").value("Bringing New Life to Old Cities"))
                .andExpect(jsonPath("$.reviewStatus").value("DRAFT"));

        mockMvc.perform(post("/api/problems/{id}/regenerate", problem.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(problem.getId()))
                .andExpect(jsonPath("$.validationStatus").value("PASSED"))
                .andExpect(jsonPath("$.answerText").value("(1) lifeless   (2) revitalize"));
        assertThat(problemRepository.count()).isPositive();
    }

    @Test
    void NEEDS_REVIEW_문항은_상태와_이유가_응답에서_구분된다() throws Exception {
        long profileId = confirmedProfile(workspaces.create());
        Problem generated = generationService.generate(profileId, PASSAGE, QuestionType.SUMMARY_BLANK, null, 1L);
        Problem needsReview = problemRepository.save(Problem.generated(generated.getWorkspaceId(), profileId, PASSAGE, null,
                QuestionType.SUMMARY_BLANK, ProblemOptions.defaults(), null, ValidationStatus.NEEDS_REVIEW,
                new ValidationReport(List.of(ValidationCheck.pass("SINGLE_WORD"),
                        ValidationCheck.fail("BLIND_SOLVE", "정답 없이 푼 AI의 답이 정답과 다르다: (1) 정답 lifeless / AI 풀이 old")), 1, List.of()),
                "mock"));

        mockMvc.perform(get("/api/problems/{id}", needsReview.getId()))
                .andExpect(jsonPath("$.validationStatus").value("NEEDS_REVIEW"))
                .andExpect(jsonPath("$.validationIssues", hasSize(1)))
                .andExpect(jsonPath("$.validationIssues[0]").value("정답 없이 푼 AI의 답이 정답과 다르다: (1) 정답 lifeless / AI 풀이 old"));
    }

    @Test
    void 없는_문항은_404() throws Exception {
        mockMvc.perform(get("/api/problems/{id}", 99999999)).andExpect(status().isNotFound());
        mockMvc.perform(post("/api/problems/{id}/regenerate", 99999999)).andExpect(status().isNotFound());
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
