package com.smwu.backend.problem.service;

import com.jayway.jsonpath.JsonPath;
import com.smwu.backend.common.exception.BusinessException;
import com.smwu.backend.common.exception.ErrorCode;
import com.smwu.backend.document.TestPdfs;
import com.smwu.backend.pastexam.extraction.QuestionType;
import com.smwu.backend.problem.domain.Problem;
import com.smwu.backend.problem.domain.ReviewStatus;
import com.smwu.backend.problem.domain.ValidationStatus;
import com.smwu.backend.problem.repository.ProblemRepository;
import com.smwu.backend.problem.type.PassageSource;
import com.smwu.backend.problem.type.ProblemOptions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 확정 프로필 + 지문 1개 → 두 유형 문제 생성·저장 (Mock LLM: generate-summary-blank.json, generate-sentence-order.json)
 */
@SpringBootTest
@AutoConfigureMockMvc
class ProblemGenerationServiceTest {

    private static final AtomicLong WORKSPACE_IDS = new AtomicLong(9000);

    /** Mock 응답과 맞는 지문 (출력예시 교과서 2과) */
    private static final PassageSource PASSAGE = new PassageSource(null, "Bringing New Life to Old Cities",
            "As cities age, neighborhoods can become old and lifeless, which may cause citizens to move away. "
                    + "When this happens, a collaboration between the local government and citizens is an effective way "
                    + "to revitalize the area. For years, the neighborhood was known for its high crime rates. "
                    + "However, having a limited budget, the government was unable to do so and had to come up with a new plan.");

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ProblemGenerationService generationService;
    @Autowired
    private ProblemRepository problemRepository;

    @Test
    void 지문_하나로_요약문_빈칸과_어구_배열을_만들고_검증_결과를_저장한다() throws Exception {
        long workspaceId = WORKSPACE_IDS.incrementAndGet();
        long profileId = confirmedProfile(workspaceId);

        Problem blank = generationService.generate(profileId, PASSAGE, QuestionType.SUMMARY_BLANK, null, 1L);
        Problem order = generationService.generate(profileId, PASSAGE, QuestionType.SENTENCE_ORDER,
                new ProblemOptions(null, null, 15), 2L);

        Problem savedBlank = problemRepository.findById(blank.getId()).orElseThrow();
        assertThat(savedBlank.getWorkspaceId()).isEqualTo(workspaceId);
        assertThat(savedBlank.getProfileId()).isEqualTo(profileId);
        assertThat(savedBlank.getValidationStatus()).isEqualTo(ValidationStatus.PASSED);
        assertThat(savedBlank.getReviewStatus()).isEqualTo(ReviewStatus.DRAFT);
        assertThat(savedBlank.getStem()).isEqualTo("윗글의 내용을 요약할 때, 빈칸 (1), (2)에 들어갈 말을 쓰시오.");
        assertThat(savedBlank.getConditions()).containsExactly("각 빈칸에 한 단어씩 쓸 것.", "윗글에 나온 단어를 형태 변경 없이 쓸 것.");
        assertThat(savedBlank.getBody()).contains("(1) __________", "(2) __________");
        assertThat(savedBlank.getAnswer().blanks()).containsExactly("lifeless", "revitalize");
        assertThat(savedBlank.getAnswerText()).isEqualTo("(1) lifeless   (2) revitalize");
        assertThat(savedBlank.getValidationReport().attempts()).isEqualTo(1);
        assertThat(savedBlank.getValidationReport().checks()).isNotEmpty().allMatch(c -> c.passed());
        assertThat(savedBlank.getOptions().blankCount()).isEqualTo(2);
        assertThat(savedBlank.getLlmModel()).isEqualTo("mock");

        Problem savedOrder = problemRepository.findById(order.getId()).orElseThrow();
        assertThat(savedOrder.getValidationStatus()).isEqualTo(ValidationStatus.PASSED);
        assertThat(savedOrder.getChoices()).hasSize(8);
        assertThat(savedOrder.getAnswer().chunkOrder()).hasSize(8);
        assertThat(savedOrder.getAnswerText()).startsWith("However, having a limited budget");
        assertThat(savedOrder.getOptions().minWords()).isEqualTo(15);
    }

    @Test
    void 지문과_맞지_않으면_재생성해도_실패해서_FAILED로_저장된다() throws Exception {
        long profileId = confirmedProfile(WORKSPACE_IDS.incrementAndGet());
        PassageSource other = new PassageSource(null, null, "Plants need sunlight and water to grow. Some plants live in deserts.");

        Problem problem = generationService.generate(profileId, other, QuestionType.SENTENCE_ORDER, null, 1L);

        assertThat(problem.getValidationStatus()).isEqualTo(ValidationStatus.FAILED);
        assertThat(problem.getValidationReport().attempts()).isEqualTo(3);
        assertThat(problem.getStem()).isNotNull();
    }

    @Test
    void 확정되지_않은_프로필로는_만들_수_없다() throws Exception {
        long workspaceId = WORKSPACE_IDS.incrementAndGet();
        uploadAndExtract(workspaceId);
        long draftId = id(mockMvc.perform(post("/api/workspaces/{id}/profiles", workspaceId)).andReturn().getResponse().getContentAsString());

        assertThatThrownBy(() -> generationService.generate(draftId, PASSAGE, QuestionType.SUMMARY_BLANK, null, 1L))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.PROFILE_NOT_CONFIRMED);
    }

    @Test
    void 아직_지원하지_않는_유형은_거부한다() throws Exception {
        long profileId = confirmedProfile(WORKSPACE_IDS.incrementAndGet());

        assertThatThrownBy(() -> generationService.generate(profileId, PASSAGE, QuestionType.SUBJ_SHORT_ANSWER, null, 1L))
                .isInstanceOf(BusinessException.class)
                .hasMessage("아직 생성할 수 없는 유형입니다: 단답형");
    }

    private long confirmedProfile(long workspaceId) throws Exception {
        uploadAndExtract(workspaceId);
        long profileId = id(mockMvc.perform(post("/api/workspaces/{id}/profiles", workspaceId))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        mockMvc.perform(post("/api/profiles/{id}/confirm", profileId)).andExpect(status().isOk());
        return profileId;
    }

    private void uploadAndExtract(long workspaceId) throws Exception {
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
        assertThat(state).isEqualTo("EXTRACTED");
    }

    private static long id(String json) {
        return ((Number) JsonPath.read(json, "$.id")).longValue();
    }
}
