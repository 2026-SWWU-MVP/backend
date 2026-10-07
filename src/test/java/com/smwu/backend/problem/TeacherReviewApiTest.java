package com.smwu.backend.problem;

import com.jayway.jsonpath.JsonPath;
import com.smwu.backend.pastexam.extraction.QuestionType;
import com.smwu.backend.problem.service.GenerationContext;
import com.smwu.backend.problem.service.GenerationContextFactory;
import com.smwu.backend.problem.service.TeacherPreferences;
import com.smwu.backend.support.TestProblems;
import com.smwu.backend.support.TestProblems.Generated;
import com.smwu.backend.support.TestWorkspaces;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 강사 검수 기록을 다음 생성에 반영하고 채택률을 본다 (#42, 쓸수록 맞춤) */
@SpringBootTest
@AutoConfigureMockMvc
class TeacherReviewApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TestWorkspaces workspaces;

    @Autowired
    private TeacherPreferences teacherPreferences;

    @Autowired
    private GenerationContextFactory contextFactory;

    @Test
    void 폐기_사유와_수정_방향과_채택이_같은_유형의_생성_맥락에_들어간다() throws Exception {
        Generated g = TestProblems.generate(mockMvc, workspaces);
        long summary = g.problemIds().get(0);
        long order = g.problemIds().get(3);

        review(summary, "{\"reviewStatus\": \"REJECTED\", \"rejectReason\": \"첫 철자 힌트 없이 빈칸 2개는 너무 어려움\"}");
        review(order, "{\"explanation\": \"분사구문 having a limited budget이 이유를 나타낸다.\"}");
        review(order, "{\"reviewStatus\": \"ACCEPTED\"}");
        // 같은 상태로 다시 보내거나 되돌리기는 기록하지 않는다
        review(order, "{\"reviewStatus\": \"ACCEPTED\"}");

        Map<QuestionType, List<String>> reviews = teacherPreferences.forWorkspace(g.workspaceId());
        assertThat(reviews.get(QuestionType.SUMMARY_BLANK)).singleElement().asString()
                .startsWith("폐기 (사유: 첫 철자 힌트 없이 빈칸 2개는 너무 어려움): (1)");
        assertThat(reviews.get(QuestionType.SENTENCE_ORDER)).hasSize(2);
        assertThat(reviews.get(QuestionType.SENTENCE_ORDER).get(0)).startsWith("채택: ");
        assertThat(reviews.get(QuestionType.SENTENCE_ORDER).get(1)).startsWith("수정: 해설 \"")
                .endsWith("→ \"분사구문 having a limited budget이 이유를 나타낸다.\"");

        // 다음 생성(같은 워크스페이스의 확정 프로필)에 들어간다
        long profileId = JsonPath.<Number>read(mockMvc.perform(get("/api/workspaces/{id}/profiles/confirmed", g.workspaceId()))
                .andReturn().getResponse().getContentAsString(), "$.id").longValue();
        GenerationContext context = contextFactory.fromConfirmedProfile(profileId).context();
        assertThat(context.teacherReviews(QuestionType.SUMMARY_BLANK)).hasSize(1);
        assertThat(context.teacherReviews(QuestionType.GRAMMAR_FIX)).isEmpty();

        // 다른 워크스페이스에는 섞이지 않는다
        assertThat(teacherPreferences.forWorkspace(workspaces.create())).isEmpty();
    }

    @Test
    void 검수_통계로_유형별_생성_작업별_채택률을_본다() throws Exception {
        Generated g = TestProblems.generate(mockMvc, workspaces);
        List<Long> ids = g.problemIds();
        review(ids.get(0), "{\"reviewStatus\": \"ACCEPTED\"}");
        review(ids.get(1), "{\"reviewStatus\": \"REJECTED\"}");
        review(ids.get(3), "{\"answerText\": \"However, having a limited budget, the government was unable to do so.\", \"reviewStatus\": \"ACCEPTED\"}");

        mockMvc.perform(get("/api/workspaces/{id}/review-stats", g.workspaceId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total.generated").value(4))
                .andExpect(jsonPath("$.total.accepted").value(2))
                .andExpect(jsonPath("$.total.rejected").value(1))
                .andExpect(jsonPath("$.total.pending").value(1))
                .andExpect(jsonPath("$.total.edited").value(1))
                .andExpect(jsonPath("$.total.acceptanceRate").value(0.667))
                .andExpect(jsonPath("$.byType[0].type").value("SUMMARY_BLANK"))
                .andExpect(jsonPath("$.byType[0].counts.acceptanceRate").value(1.0))
                .andExpect(jsonPath("$.byType[1].counts.acceptanceRate").value(0.5))
                .andExpect(jsonPath("$.byJob[0].counts.generated").value(4));

        TestWorkspaces.Member other = workspaces.newAcademy();
        mockMvc.perform(get("/api/workspaces/{id}/review-stats", g.workspaceId()).header("X-User-Id", other.userId()))
                .andExpect(status().isNotFound());
        mockMvc.perform(patch("/api/problems/{id}", ids.get(2)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reviewStatus\": \"REJECTED\", \"rejectReason\": \"%s\"}".formatted("가".repeat(301))))
                .andExpect(status().isBadRequest());
    }

    private void review(long problemId, String body) throws Exception {
        mockMvc.perform(patch("/api/problems/{id}", problemId).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
    }
}
