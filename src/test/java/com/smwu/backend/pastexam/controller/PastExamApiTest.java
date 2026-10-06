package com.smwu.backend.pastexam.controller;

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
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;


import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 기출 업로드 → 추출(비동기, Mock LLM) → 검수·수정 API 흐름.
 * Mock 응답: resources/mock-llm/extract-questions.json (지문 2개, 객관식 1 + 서답형 2)
 */
@SpringBootTest
@AutoConfigureMockMvc
class PastExamApiTest {

    /** 테스트끼리 데이터가 섞이지 않게 워크스페이스 ID를 다르게 쓴다 */
    @Autowired
    private TestWorkspaces workspaces;

    @Autowired
    private MockMvc mockMvc;

    @Test
    void 업로드_추출_조회_수정_흐름() throws Exception {
        long workspaceId = workspaces.create();

        // 1. 업로드
        String uploaded = mockMvc.perform(upload(workspaceId, pdf(TestPdfs.textPdf(2))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("UPLOADED"))
                .andExpect(jsonPath("$.workspaceId").value(workspaceId))
                .andExpect(jsonPath("$.pageCount").value(2))
                .andExpect(jsonPath("$.scanned").value(false))
                .andExpect(jsonPath("$.originalFilename").value("2025-1-mid.pdf"))
                .andReturn().getResponse().getContentAsString();
        long examId = ((Number) JsonPath.read(uploaded, "$.id")).longValue();

        // 2. 추출 시작 → 202, 3. 완료될 때까지 폴링
        mockMvc.perform(post("/api/past-exams/{id}/analyze", examId))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("EXTRACTING"));
        waitUntilExtracted(examId);

        mockMvc.perform(get("/api/past-exams/{id}", examId))
                .andExpect(jsonPath("$.objectiveCount").value(1))
                .andExpect(jsonPath("$.subjectiveCount").value(2))
                .andExpect(jsonPath("$.extractedAt").isNotEmpty());
        mockMvc.perform(get("/api/workspaces/{id}/past-exams", workspaceId))
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].status").value("EXTRACTED"));

        // 4. 추출 결과: 점검 이슈 없음
        String result = mockMvc.perform(get("/api/past-exams/{id}/questions", examId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.passages", hasSize(2)))
                .andExpect(jsonPath("$.questions", hasSize(3)))
                .andExpect(jsonPath("$.questions[0].section").value("OBJECTIVE"))
                .andExpect(jsonPath("$.questions[0].type").value("OBJ_GRAMMAR"))
                .andExpect(jsonPath("$.questions[0].passageCodes[0]").value("P2"))
                .andExpect(jsonPath("$.questions[1].conditions", hasSize(2)))
                .andExpect(jsonPath("$.issueCount").value(0))
                .andReturn().getResponse().getContentAsString();
        long grammarPassageId = ((Number) JsonPath.read(result, "$.passages[1].id")).longValue();
        long subjectiveQuestionId = ((Number) JsonPath.read(result, "$.questions[2].id")).longValue();

        // 5. 지문에서 밑줄 대괄호 하나를 지우면 어법 문항에 이슈가 생긴다
        mockMvc.perform(patch("/api/past-passages/{id}", grammarPassageId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\": \"It ①[was] and ②[taking] and ③[was] and ④[having] and which.\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.passages[1].edited").value(true))
                .andExpect(jsonPath("$.questions[0].issues", hasItem("어법 객관식 밑줄 대괄호가 4개 (5개 예상, 원본 확인 필요)")))
                .andExpect(jsonPath("$.issueCount").value(1));

        // 6. 문항 수정: 정답 입력, 배점 지우기
        mockMvc.perform(patch("/api/past-questions/{id}", subjectiveQuestionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"answer\": \"However, having a limited budget, the government was unable to do so.\", \"clearPoints\": true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.questions[2].edited").value(true))
                .andExpect(jsonPath("$.questions[2].answer").value("However, having a limited budget, the government was unable to do so."))
                .andExpect(jsonPath("$.questions[2].points").doesNotExist())
                .andExpect(jsonPath("$.questions[1].edited").value(false));
    }

    @Test
    void 없는_지문을_참조하도록_수정하면_400() throws Exception {
        long examId = uploadAndExtract(workspaces.create());
        String result = mockMvc.perform(get("/api/past-exams/{id}/questions", examId))
                .andReturn().getResponse().getContentAsString();
        long questionId = ((Number) JsonPath.read(result, "$.questions[0].id")).longValue();

        mockMvc.perform(patch("/api/past-questions/{id}", questionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"passageCodes\": [\"P1\", \"P9\"]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("없는 지문을 참조합니다: [P9]"));
    }

    @Test
    void 추출_전에는_수정할_수_없다() throws Exception {
        long workspaceId = workspaces.create();
        String uploaded = mockMvc.perform(upload(workspaceId, pdf(TestPdfs.blankPdf(1))))
                .andExpect(jsonPath("$.scanned").value(true))
                .andReturn().getResponse().getContentAsString();
        long examId = ((Number) JsonPath.read(uploaded, "$.id")).longValue();

        mockMvc.perform(get("/api/past-exams/{id}/questions", examId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UPLOADED"))
                .andExpect(jsonPath("$.questions", hasSize(0)));
    }

    @Test
    void PDF가_아니면_400() throws Exception {
        MockMultipartFile hwp = new MockMultipartFile("file", "exam.hwp", "application/octet-stream", new byte[]{1, 2, 3, 4, 5, 6});

        mockMvc.perform(upload(workspaces.create(), hwp))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_FILE"))
                .andExpect(jsonPath("$.message").value("PDF 파일만 올릴 수 있습니다. HWP나 사진은 PDF로 변환해 주세요."));
    }

    @Test
    void 시험_정보가_빠지거나_잘못되면_400() throws Exception {
        mockMvc.perform(multipart("/api/workspaces/{id}/past-exams", workspaces.create())
                        .file(pdf(TestPdfs.textPdf(1)))
                        .param("examYear", "2025")
                        .param("semester", "3")
                        .param("examType", "QUIZ"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        mockMvc.perform(multipart("/api/workspaces/{id}/past-exams", workspaces.create())
                        .param("examYear", "2025").param("semester", "1").param("examType", "MIDTERM"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 삭제하면_조회되지_않는다() throws Exception {
        long examId = uploadAndExtract(workspaces.create());

        mockMvc.perform(delete("/api/past-exams/{id}", examId)).andExpect(status().isNoContent());
        mockMvc.perform(get("/api/past-exams/{id}", examId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    private long uploadAndExtract(long workspaceId) throws Exception {
        String uploaded = mockMvc.perform(upload(workspaceId, pdf(TestPdfs.textPdf(1))))
                .andReturn().getResponse().getContentAsString();
        long examId = ((Number) JsonPath.read(uploaded, "$.id")).longValue();
        mockMvc.perform(post("/api/past-exams/{id}/analyze", examId)).andExpect(status().isAccepted());
        waitUntilExtracted(examId);
        return examId;
    }

    private void waitUntilExtracted(long examId) throws Exception {
        String status = null;
        for (int i = 0; i < 100; i++) {
            String body = mockMvc.perform(get("/api/past-exams/{id}", examId)).andReturn().getResponse().getContentAsString();
            status = JsonPath.read(body, "$.status");
            if (!"EXTRACTING".equals(status)) {
                break;
            }
            Thread.sleep(100);
        }
        assertThat(status).isEqualTo("EXTRACTED");
    }

    private static MockMultipartHttpServletRequestBuilder upload(long workspaceId, MockMultipartFile file) {
        return (MockMultipartHttpServletRequestBuilder) multipart("/api/workspaces/{id}/past-exams", workspaceId)
                .file(file)
                .param("examYear", "2025")
                .param("semester", "1")
                .param("examType", "MIDTERM");
    }

    private static MockMultipartFile pdf(byte[] content) {
        return new MockMultipartFile("file", "2025-1-mid.pdf", "application/pdf", content);
    }
}
