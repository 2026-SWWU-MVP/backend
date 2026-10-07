package com.smwu.backend.worksheet;

import com.jayway.jsonpath.JsonPath;
import com.smwu.backend.support.TestProblems;
import com.smwu.backend.support.TestProblems.Generated;
import com.smwu.backend.support.TestWorkspaces;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 문제 검수(수정·채택·폐기)와 시험지 구성 (#16, Mock LLM) */
@SpringBootTest
@AutoConfigureMockMvc
class ReviewAndWorksheetApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TestWorkspaces workspaces;

    @Test
    void 문항을_고치고_채택_폐기하면_검수자와_수정_여부가_남는다() throws Exception {
        Generated g = generate();
        long problem = g.problemIds().get(0);

        mockMvc.perform(patch("/api/problems/{id}", problem).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"stem\": \" 윗글을 요약할 때 빈칸에 알맞은 말을 쓰시오. \", \"answerText\": \"(1) lifeless (2) revitalize\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stem").value("윗글을 요약할 때 빈칸에 알맞은 말을 쓰시오."))
                .andExpect(jsonPath("$.answerText").value("(1) lifeless (2) revitalize"))
                .andExpect(jsonPath("$.edited").value(true))
                .andExpect(jsonPath("$.reviewStatus").value("DRAFT"))
                .andExpect(jsonPath("$.reviewedBy").isNumber());

        // 같은 내용이면 edited가 바뀌지 않는다 (채택만)
        long other = g.problemIds().get(3);
        mockMvc.perform(patch("/api/problems/{id}", other).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reviewStatus\": \"ACCEPTED\"}"))
                .andExpect(jsonPath("$.reviewStatus").value("ACCEPTED"))
                .andExpect(jsonPath("$.edited").value(false));
        mockMvc.perform(patch("/api/problems/{id}", g.problemIds().get(1)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reviewStatus\": \"REJECTED\"}"))
                .andExpect(jsonPath("$.reviewStatus").value("REJECTED"));

        mockMvc.perform(get("/api/workspaces/{id}/problems", g.workspaceId()).param("reviewStatus", "ACCEPTED"))
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id").value(other));
        mockMvc.perform(get("/api/workspaces/{id}/problems", g.workspaceId())).andExpect(jsonPath("$", hasSize(4)));

        // 다시 생성하면 수정·검수가 초기화된다
        mockMvc.perform(post("/api/problems/{id}/regenerate", problem))
                .andExpect(jsonPath("$.edited").value(false))
                .andExpect(jsonPath("$.reviewStatus").value("DRAFT"))
                .andExpect(jsonPath("$.reviewedBy").doesNotExist());

        // 다른 학원은 고칠 수 없고, 너무 긴 값은 400
        TestWorkspaces.Member b = workspaces.newAcademy();
        mockMvc.perform(patch("/api/problems/{id}", problem).header("X-User-Id", b.userId())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reviewStatus\": \"ACCEPTED\"}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(patch("/api/problems/{id}", problem).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"stem\": \"%s\"}".formatted("가".repeat(1001))))
                .andExpect(status().isBadRequest());
        mockMvc.perform(patch("/api/problems/{id}", problem).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reviewStatus\": \"DONE\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 채택한_문항으로_지문별로_묶인_시험지를_만들고_학원_안에서_공유한다() throws Exception {
        Generated g = generate();
        List<Long> ids = g.problemIds();
        for (long id : ids) {
            accept(id);
        }
        // 요청 순서: B 어구 배열, A 요약문 빈칸, B 요약문 빈칸, A 어구 배열 → B 묶음, A 묶음
        String body = JsonMapper.builder().build().writeValueAsString(Map.of(
                "title", "2과 서술형 대비", "headerText", "2026 1학기 중간고사 대비",
                "problemIds", List.of(ids.get(3), ids.get(0), ids.get(2), ids.get(1))));
        String created = mockMvc.perform(post("/api/workspaces/{id}/worksheets", g.workspaceId())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.problemCount").value(4))
                .andExpect(jsonPath("$.showLogo").value(true))
                .andExpect(jsonPath("$.createdByName").value("테스트원장"))
                .andExpect(jsonPath("$.sections", hasSize(2)))
                .andExpect(jsonPath("$.sections[0].passageTitle").value("Crime and Budget"))
                .andExpect(jsonPath("$.sections[0].passageText").isNotEmpty())
                .andExpect(jsonPath("$.sections[0].items[0].no").value(1))
                .andExpect(jsonPath("$.sections[0].items[0].problem.id").value(ids.get(3)))
                .andExpect(jsonPath("$.sections[0].items[1].no").value(2))
                .andExpect(jsonPath("$.sections[0].items[1].problem.id").value(ids.get(2)))
                .andExpect(jsonPath("$.sections[1].items[0].no").value(1))
                .andExpect(jsonPath("$.sections[1].items[0].problem.id").value(ids.get(0)))
                .andReturn().getResponse().getContentAsString();
        long worksheetId = id(created);

        // 같은 학원 강사도 목록·본문을 본다
        long teacher = workspaces.newTeacher();
        mockMvc.perform(get("/api/workspaces/{id}/worksheets", g.workspaceId()).header("X-User-Id", teacher))
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].problemCount").value(4))
                .andExpect(jsonPath("$[0].createdByName").value("테스트원장"));
        mockMvc.perform(get("/api/worksheets/{id}", worksheetId).header("X-User-Id", teacher))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sections[1].items[1].problem.id").value(ids.get(1)));

        // 수정: 제목, 로고 끄기, 문항 줄이기
        mockMvc.perform(patch("/api/worksheets/{id}", worksheetId).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\": \"2과 요약문 빈칸\", \"showLogo\": false, \"headerText\": \"\", \"problemIds\": [%d, %d]}"
                                .formatted(ids.get(0), ids.get(2))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("2과 요약문 빈칸"))
                .andExpect(jsonPath("$.showLogo").value(false))
                .andExpect(jsonPath("$.headerText").doesNotExist())
                .andExpect(jsonPath("$.problemCount").value(2))
                .andExpect(jsonPath("$.sections", hasSize(2)));

        // 다른 학원은 볼 수 없다
        TestWorkspaces.Member b = workspaces.newAcademy();
        mockMvc.perform(get("/api/worksheets/{id}", worksheetId).header("X-User-Id", b.userId())).andExpect(status().isNotFound());

        mockMvc.perform(delete("/api/worksheets/{id}", worksheetId)).andExpect(status().isNoContent());
        mockMvc.perform(get("/api/worksheets/{id}", worksheetId)).andExpect(status().isNotFound());
    }

    @Test
    void 채택_안_된_문항_중복_다른_워크스페이스_문항은_넣을_수_없다() throws Exception {
        Generated g = generate();
        List<Long> ids = g.problemIds();
        accept(ids.get(0));

        create(g.workspaceId(), "[%d, %d]".formatted(ids.get(0), ids.get(1)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("채택한 문항만 시험지에 넣을 수 있습니다: " + ids.get(1)));
        create(g.workspaceId(), "[%d, %d]".formatted(ids.get(0), ids.get(0))).andExpect(status().isBadRequest());
        create(g.workspaceId(), "[]").andExpect(status().isBadRequest());

        long otherWorkspace = workspaces.create();
        create(otherWorkspace, "[%d]".formatted(ids.get(0)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("이 워크스페이스의 문항이 아닙니다: " + ids.get(0)));
    }

    private org.springframework.test.web.servlet.ResultActions create(long workspaceId, String problemIds) throws Exception {
        return mockMvc.perform(post("/api/workspaces/{id}/worksheets", workspaceId).contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\": \"시험지\", \"problemIds\": %s}".formatted(problemIds)));
    }

    private void accept(long problemId) throws Exception {
        TestProblems.accept(mockMvc, problemId);
    }

    private Generated generate() throws Exception {
        return TestProblems.generate(mockMvc, workspaces);
    }

    private static long id(String json) {
        return ((Number) JsonPath.read(json, "$.id")).longValue();
    }
}
