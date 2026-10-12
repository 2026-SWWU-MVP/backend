package com.smwu.backend.demo;

import com.jayway.jsonpath.JsonPath;
import com.smwu.backend.academy.domain.InviteCode;
import com.smwu.backend.academy.repository.InviteCodeRepository;
import com.smwu.backend.document.TestPdfs;
import com.smwu.backend.support.TestUserMockMvcCustomizer;
import com.smwu.backend.user.repository.UserRepository;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 시연 시나리오 1~7단계를 데모 데이터(demo 프로필)로 처음부터 끝까지 재현한다 (#24, Mock LLM).
 * 다른 테스트와 데이터가 섞이지 않게 별도 인메모리 DB를 쓴다.
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:demodb;MODE=PostgreSQL;DB_CLOSE_DELAY=-1")
@ActiveProfiles("demo")
@AutoConfigureMockMvc
class DemoScenarioTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private DemoDataSeeder seeder;

    @Autowired
    private InviteCodeRepository inviteCodeRepository;

    @Autowired
    private UserRepository userRepository;

    @Test
    void 시연_시나리오_1부터_7까지() throws Exception {
        // 1. 원장 로그인 → 로고 확인 → 초대 코드 발급
        long owner = login(DemoDataSeeder.OWNER_LOGIN_ID);
        as(owner, get("/api/academy"))
                .andExpect(jsonPath("$.name").value("파인로드영어"))
                .andExpect(jsonPath("$.hasLogo").value(true))
                .andExpect(jsonPath("$.teacherCount").value(2));
        String code = JsonPath.read(as(owner, post("/api/academy/invites")).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(), "$.code");

        // 2. 새 강사가 가입하고 초대 코드로 합류
        long teacher = id(mockMvc.perform(post("/api/auth/signup").header(TestUserMockMvcCustomizer.ANONYMOUS, "1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loginId\": \"newteacher\", \"password\": \"1234\", \"name\": \"최강사\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.userId");
        as(teacher, post("/api/academies/join").contentType(MediaType.APPLICATION_JSON).content("{\"code\": \"%s\"}".formatted(code)))
                .andExpect(jsonPath("$.role").value("TEACHER"));

        // 3. 강사가 건대부고 1학년 워크스페이스에 기출을 올리고 분석된 프로필 확인
        String cards = as(teacher, get("/api/workspaces")).andReturn().getResponse().getContentAsString();
        List<Number> ids = JsonPath.read(cards, "$[?(@.title == '건대부고 1학년')].id");
        long workspace = ids.get(0).longValue();
        long exam = id(as(teacher, multipart("/api/workspaces/{id}/past-exams", workspace)
                        .file(new MockMultipartFile("file", "건대부고 2025 1학기 중간.pdf", "application/pdf", TestPdfs.textPdf(1)))
                        .param("examYear", "2025").param("semester", "1").param("examType", "MIDTERM"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id");
        as(teacher, post("/api/past-exams/{id}/analyze", exam)).andExpect(status().isAccepted());
        waitFor(teacher, "/api/past-exams/" + exam, "EXTRACTED");
        long v1 = id(as(teacher, post("/api/workspaces/{id}/profiles", workspace))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.typeMixPerPassage.SENTENCE_ORDER").value(1))
                .andReturn().getResponse().getContentAsString(), "$.id");

        // 4. 강사 의견 → 변경점 확인 → 확정
        long v2 = id(as(teacher, post("/api/profiles/{id}/feedback", v1).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\": \"이번엔 어구 배열 위주로 낸다고 함\", \"persistent\": false}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.typeMixPerPassage.SENTENCE_ORDER").value(2))
                .andExpect(jsonPath("$.changeSummary", hasItem("지문당 유형 구성: 요약문 빈칸 2 → 1, 어구 배열 1 → 2")))
                .andReturn().getResponse().getContentAsString(), "$.id");
        as(teacher, post("/api/profiles/{id}/confirm", v2)).andExpect(jsonPath("$.status").value("CONFIRMED"));

        // 5. 시험범위(데모 자료) 지문으로 생성 → 어구 배열 비중 확인 → 검수
        String materials = as(teacher, get("/api/workspaces/{id}/materials", workspace)).andReturn().getResponse().getContentAsString();
        long material = ((Number) JsonPath.read(materials, "$[0].id")).longValue();
        List<Number> passages = JsonPath.read(as(teacher, get("/api/materials/{id}/passages", material))
                .andReturn().getResponse().getContentAsString(), "$[*].id");
        long job = id(as(teacher, post("/api/workspaces/{id}/generation-jobs", workspace).contentType(MediaType.APPLICATION_JSON)
                        .content(JsonMapper.builder().build().writeValueAsString(Map.of("profileId", v2, "passageIds", passages))))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString(), "$.id");
        waitFor(teacher, "/api/generation-jobs/" + job, "COMPLETED");
        String problems = as(teacher, get("/api/generation-jobs/{id}/problems", job)).andReturn().getResponse().getContentAsString();
        List<String> types = JsonPath.read(problems, "$[*].type");
        assertThat(types.stream().filter("SENTENCE_ORDER"::equals).count())
                .isGreaterThan(types.stream().filter("SUMMARY_BLANK"::equals).count());
        List<Number> passed = JsonPath.read(problems, "$[?(@.validationStatus == 'PASSED')].id");
        assertThat(passed).isNotEmpty();
        for (Number p : passed) {
            as(teacher, patch("/api/problems/{id}", p.longValue()).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"reviewStatus\": \"ACCEPTED\"}")).andExpect(status().isOk());
        }

        // 6. 로고가 들어간 문제지·정답지 PDF
        long worksheet = id(as(teacher, post("/api/workspaces/{id}/worksheets", workspace).contentType(MediaType.APPLICATION_JSON)
                        .content(JsonMapper.builder().build().writeValueAsString(Map.of(
                                "title", "건대부고1 2과 서술형", "headerText", "건대부고1 1학기 중간고사 서답형 대비 - 교과서 2과",
                                "problemIds", passed))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id");
        for (String pdf : List.of("pdf", "answer-pdf")) {
            byte[] bytes = as(teacher, get("/api/worksheets/{id}/" + pdf, worksheet)).andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsByteArray();
            try (PDDocument document = Loader.loadPDF(bytes)) {
                var resources = document.getPage(0).getResources();
                boolean hasLogo = false;
                for (var name : resources.getXObjectNames()) {
                    hasLogo |= resources.isImageXObject(name);
                }
                assertThat(hasLogo).as(pdf + " 로고").isTrue();
            }
        }

        // 7. 다른 강사(teacher1)로 로그인해 같은 프로필·시험지가 보이는지
        long teacher1 = login("teacher1");
        as(teacher1, get("/api/workspaces/{id}/profiles/confirmed", workspace)).andExpect(jsonPath("$.id").value(v2));
        as(teacher1, get("/api/workspaces/{id}/worksheets", workspace))
                .andExpect(jsonPath("$[0].id").value(worksheet))
                .andExpect(jsonPath("$[0].createdByName").value("최강사"));
        as(teacher1, get("/api/workspaces/{id}", workspace))
                .andExpect(jsonPath("$.summary.profileStatus").value("CONFIRMED"))
                .andExpect(jsonPath("$.summary.latestWorksheet.id").value(worksheet));
    }

    @Test
    void 더미_데이터로_모든_화면에_데이터가_있다() throws Exception {
        // #62: 프론트에 기출·프로필 화면이 없어도 화면 개발·시연을 할 수 있도록 끝까지 진행된 데이터를 넣어 둔다
        long owner = login(DemoDataSeeder.OWNER_LOGIN_ID);
        String cards = as(owner, get("/api/workspaces")).andReturn().getResponse().getContentAsString();
        long workspace = JsonPath.<List<Number>>read(cards, "$[?(@.title == '압구정고 1학년')].id").get(0).longValue();
        long inReview = JsonPath.<List<Number>>read(cards, "$[?(@.title == '현대고 2학년')].id").get(0).longValue();

        // 학원 관리: 상태별 초대 코드 (#66)
        String invites = as(owner, get("/api/academy/invites")).andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<List<String>>read(invites, "$[*].status")).contains("ACTIVE", "USED", "EXPIRED", "CANCELED");
        assertThat(JsonPath.<List<String>>read(invites, "$[?(@.code == 'WELCM3')].usedByName")).containsExactly("박강사");

        // 대시보드 카드
        as(owner, get("/api/workspaces/{id}", workspace))
                .andExpect(jsonPath("$.summary.pastExamCount").value(3))
                .andExpect(jsonPath("$.summary.profileStatus").value("CONFIRMED"))
                .andExpect(jsonPath("$.summary.confirmedProfileVersion").value(2))
                .andExpect(jsonPath("$.summary.latestWorksheet.title").value("압구정고1 2과 서술형 대비"));
        as(owner, get("/api/workspaces/{id}", inReview)).andExpect(jsonPath("$.summary.profileStatus").value("DRAFT"));

        // 기출 → 추출 결과
        String exams = as(owner, get("/api/workspaces/{id}/past-exams", workspace)).andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<List<String>>read(exams, "$[*].status")).containsOnly("EXTRACTED").hasSize(3);
        long exam = ((Number) JsonPath.read(exams, "$[0].id")).longValue();
        as(owner, get("/api/past-exams/{id}/questions", exam)).andExpect(status().isOk())
                .andExpect(jsonPath("$.passages.length()").value(2));

        // 확정 프로필: 기출 근거가 연결된 규칙
        long profile = id(as(owner, get("/api/workspaces/{id}/profiles/confirmed", workspace))
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.typeMixPerPassage.SUMMARY_BLANK").value(1))
                .andExpect(jsonPath("$.typeMixPerPassage.SENTENCE_ORDER").value(1))
                .andExpect(jsonPath("$.typeMixPerPassage.GRAMMAR_FIX").value(1))
                .andExpect(jsonPath("$.stats.examCount").value(3))
                .andReturn().getResponse().getContentAsString(), "$.id");
        as(owner, get("/api/workspaces/{id}/profiles", workspace)).andExpect(jsonPath("$.length()").value(2));

        // 학교 DB: 우리 기출 3회분 + 다른 학원 1회분
        as(owner, get("/api/workspaces/{id}/school-trends", workspace))
                .andExpect(jsonPath("$.examCount").value(4))
                .andExpect(jsonPath("$.contributorCount").value(2));

        // 생성 작업 2개 → 검수 통계 → 시험지
        String jobs = as(owner, get("/api/workspaces/{id}/generation-jobs", workspace)).andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<List<String>>read(jobs, "$[*].status")).containsOnly("COMPLETED").hasSize(2);
        String stats = as(owner, get("/api/workspaces/{id}/review-stats", workspace)).andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<Integer>read(stats, "$.total.generated")).isEqualTo(8);
        assertThat(JsonPath.<Integer>read(stats, "$.total.accepted")).isEqualTo(5);
        assertThat(JsonPath.<Integer>read(stats, "$.total.edited")).isEqualTo(1);
        String accepted = as(owner, get("/api/workspaces/{id}/problems?reviewStatus=ACCEPTED", workspace))
                .andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<List<String>>read(accepted, "$[*].validationStatus")).containsOnly("PASSED");
        String worksheets = as(owner, get("/api/workspaces/{id}/worksheets", workspace)).andReturn().getResponse().getContentAsString();
        long worksheet = ((Number) JsonPath.read(worksheets, "$[0].id")).longValue();
        as(owner, get("/api/worksheets/{id}", worksheet))
                .andExpect(jsonPath("$.problemCount").value(4))
                .andExpect(jsonPath("$.sections.length()").value(2));
        as(owner, get("/api/worksheets/{id}/pdf", worksheet)).andExpect(status().isOk());

        // 확정 프로필로 바로 새로 생성할 수 있다 (Mock LLM)
        String materials = as(owner, get("/api/workspaces/{id}/materials", workspace)).andReturn().getResponse().getContentAsString();
        long material = ((Number) JsonPath.read(materials, "$[0].id")).longValue();
        List<Number> passages = JsonPath.read(as(owner, get("/api/materials/{id}/passages", material))
                .andReturn().getResponse().getContentAsString(), "$[*].id");
        long job = id(as(owner, post("/api/workspaces/{id}/generation-jobs", workspace).contentType(MediaType.APPLICATION_JSON)
                        .content(JsonMapper.builder().build().writeValueAsString(Map.of("profileId", profile, "passageIds", passages))))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString(), "$.id");
        waitFor(owner, "/api/generation-jobs/" + job, "COMPLETED");
        String problems = as(owner, get("/api/generation-jobs/{id}/problems", job)).andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<List<String>>read(problems, "$[*].type")).hasSize(6);
        assertThat(JsonPath.<List<Number>>read(problems, "$[?(@.validationStatus == 'PASSED')].id")).isNotEmpty();
    }

    @Test
    void 초대_코드가_없던_기존_데모_DB에는_다시_실행할_때_채운다() {
        // #66: 자동 배포는 DB를 비우지 않으므로, 이미 데모 데이터가 있는 서버에도 초대 코드가 들어가야 한다
        Long academyId = userRepository.findByLoginId(DemoDataSeeder.OWNER_LOGIN_ID).orElseThrow().getAcademyId();
        inviteCodeRepository.deleteAll(inviteCodeRepository.findByAcademyIdOrderByIdDesc(academyId));

        seeder.seed();
        assertThat(inviteCodeRepository.findByAcademyIdOrderByIdDesc(academyId)).extracting(InviteCode::getCode)
                .containsExactlyInAnyOrder("FNRD26", "WELCM3", "XPRD47", "CNCL58");

        seeder.seed();
        assertThat(inviteCodeRepository.findByAcademyIdOrderByIdDesc(academyId)).hasSize(4);
    }

    private long login(String loginId) throws Exception {
        return id(mockMvc.perform(post("/api/auth/login").header(TestUserMockMvcCustomizer.ANONYMOUS, "1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loginId\": \"%s\", \"password\": \"%s\"}".formatted(loginId, DemoDataSeeder.PASSWORD)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.userId");
    }

    private void waitFor(long user, String url, String expected) throws Exception {
        String state = null;
        for (int i = 0; i < 150 && !expected.equals(state); i++) {
            Thread.sleep(50);
            state = JsonPath.read(as(user, get(url)).andReturn().getResponse().getContentAsString(), "$.status");
        }
        assertThat(state).isEqualTo(expected);
    }

    private ResultActions as(long userId, AbstractMockHttpServletRequestBuilder<?> request) throws Exception {
        return mockMvc.perform(request.header("X-User-Id", userId));
    }

    private static long id(String json, String path) {
        return ((Number) JsonPath.read(json, path)).longValue();
    }
}
