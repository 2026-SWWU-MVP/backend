package com.smwu.backend;

import com.jayway.jsonpath.JsonPath;
import com.smwu.backend.pastexam.extraction.QuestionType;
import com.smwu.backend.problem.domain.Problem;
import com.smwu.backend.problem.domain.ValidationStatus;
import com.smwu.backend.problem.repository.ProblemRepository;
import com.smwu.backend.support.TestWorkspaces;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 전체 흐름 점검 + 발표용 수치 측정 (이슈 #23). 실제 OpenAI를 호출한다 (비용 발생, 약 10~15분).
 * <ol>
 *   <li>기출 PDF 업로드 → 추출 → 프로필 v1 → 강사 의견 반영 v2 → 확정</li>
 *   <li>시험범위 PDF 업로드 → 지문 분리</li>
 *   <li>긴 지문부터 골라 확정 프로필의 지문당 유형 구성대로 약 50문항 생성 작업</li>
 *   <li>규칙 검증 통과율, 검증이 걸러낸 오류(적용 전 vs 후), 유형 구성 일치도 집계 + 사람 검수표</li>
 * </ol>
 * <pre>
 * ./gradlew llmTest --tests '*EndToEndExperiment'
 * EXAM_FILTER=현대 MATERIAL_FILTER=압구정 TARGET_PROBLEMS=30 ./gradlew llmTest --tests '*EndToEndExperiment'
 * </pre>
 * 결과: build/experiments/e2e/{기출}.md (요약 + 검수표). 검수표의 "정답 오류" 칸을 사람이 채운다.
 */
@Tag("llm")
@EnabledIfEnvironmentVariable(named = "LLM_API_KEY", matches = ".+")
@EnabledIfEnvironmentVariable(named = "LLM_MODEL", matches = ".+")
@SpringBootTest(properties = {
        "app.llm.provider=openai",
        "app.llm.api-key=${LLM_API_KEY}",
        "app.llm.model=${LLM_MODEL}",
        "app.llm.base-url=https://api.openai.com/v1",
        "app.llm.timeout-seconds=300"
})
@AutoConfigureMockMvc
class EndToEndExperiment {

    private static final Path INPUT_DIR = Path.of("experiments", "exams");
    private static final Path OUTPUT_DIR = Path.of("build", "experiments", "e2e");
    private static final int MIN_WORDS = 90;
    private static final String FEEDBACK = "학생들 말로는 이번 서술형은 어구 배열 위주로 낸다고 하심";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ProblemRepository problemRepository;

    @Autowired
    private TestWorkspaces workspaces;

    private final JsonMapper objectMapper = JsonMapper.builder().build();
    private final Map<String, Long> timings = new LinkedHashMap<>();

    @Test
    void 기출부터_문제_생성까지_전체_흐름() throws Exception {
        Path examPdf = pick(env("EXAM_FILTER", "압구정"));
        Path materialPdf = pick(env("MATERIAL_FILTER", "압구정"));
        Assumptions.assumeTrue(examPdf != null && materialPdf != null, "experiments/exams/에 PDF가 없어서 건너뜀");
        int target = Integer.parseInt(env("TARGET_PROBLEMS", "50"));
        Files.createDirectories(OUTPUT_DIR);
        long workspaceId = workspaces.create();

        // 1. 기출 → 프로필 → 강사 의견 → 확정
        long started = System.currentTimeMillis();
        long examId = id(mockMvc.perform(multipart("/api/workspaces/{id}/past-exams", workspaceId)
                        .file(new MockMultipartFile("file", examPdf.getFileName().toString(), "application/pdf", Files.readAllBytes(examPdf)))
                        .param("examYear", "2025").param("semester", "2").param("examType", "MIDTERM"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        mockMvc.perform(post("/api/past-exams/{id}/analyze", examId)).andExpect(status().isAccepted());
        String exam = poll("/api/past-exams/" + examId, "EXTRACTING", 3000, 200);
        assertThat((String) JsonPath.read(exam, "$.status")).isEqualTo("EXTRACTED");
        lap("기출 추출", started);

        started = System.currentTimeMillis();
        String v1 = mockMvc.perform(post("/api/workspaces/{id}/profiles", workspaceId))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        lap("프로필 v1 (통계 + 규칙 요약)", started);

        started = System.currentTimeMillis();
        String v2 = mockMvc.perform(post("/api/profiles/{id}/feedback", id(v1)).contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("text", FEEDBACK, "persistent", false))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        lap("강사 의견 반영 v2", started);
        String confirmed = mockMvc.perform(post("/api/profiles/{id}/confirm", id(v2)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        Map<String, Integer> mix = JsonPath.read(confirmed, "$.typeMixPerPassage");
        int perPassage = mix.values().stream().mapToInt(Integer::intValue).sum();

        // 2. 시험범위 PDF → 지문 분리
        started = System.currentTimeMillis();
        long materialId = id(mockMvc.perform(multipart("/api/workspaces/{id}/materials", workspaceId)
                        .file(new MockMultipartFile("file", materialPdf.getFileName().toString(), "application/pdf",
                                Files.readAllBytes(materialPdf))))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString());
        String material = poll("/api/materials/" + materialId, "SPLITTING", 3000, 200);
        assertThat((String) JsonPath.read(material, "$.status")).isEqualTo("SPLIT");
        lap("시험범위 지문 분리", started);
        String passagesJson = mockMvc.perform(get("/api/materials/{id}/passages", materialId)).andReturn().getResponse().getContentAsString();
        List<Map<String, Object>> passages = JsonPath.read(passagesJson, "$[*]");
        int passageCount = Math.min(20, Math.max(1, target / perPassage));
        List<Long> passageIds = passages.stream()
                .filter(p -> ((Number) p.get("wordCount")).intValue() >= MIN_WORDS)
                .sorted(Comparator.comparingInt((Map<String, Object> p) -> ((Number) p.get("wordCount")).intValue()).reversed())
                .limit(passageCount)
                .map(p -> ((Number) p.get("id")).longValue())
                .toList();

        // 3. 생성 작업 (확정 프로필의 지문당 유형 구성 그대로)
        long jobId = id(mockMvc.perform(post("/api/workspaces/{id}/generation-jobs", workspaceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("profileId", id(confirmed), "passageIds", passageIds))))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString());
        String job = poll("/api/generation-jobs/" + jobId, "RUNNING", 5000, 360);
        // 폴링 간격·노트북 절전과 무관하게 서버가 기록한 시작~종료 시각으로 잰다
        timings.put("문제 생성 작업 (" + JsonPath.read(job, "$.total") + "문항, 동시 4개)", java.time.Duration.between(
                java.time.LocalDateTime.parse(JsonPath.read(job, "$.createdAt")),
                java.time.LocalDateTime.parse(JsonPath.read(job, "$.finishedAt"))).toMillis());
        System.out.println("생성 작업: " + job);

        List<Problem> problems = problemRepository.findByGenerationJobIdOrderByJobSlotAscIdAsc(jobId);
        String name = examPdf.getFileName().toString().replaceFirst("(?i)\\.pdf$", "");
        String md = report(name, materialPdf.getFileName().toString(), v1, v2, confirmed, passages.size(), passageIds.size(), job,
                problems);
        Files.writeString(OUTPUT_DIR.resolve(name + ".md"), md, StandardCharsets.UTF_8);
        System.out.println("결과: " + OUTPUT_DIR.toAbsolutePath());
    }

    private String report(String exam, String material, String v1, String v2, String confirmed, int splitCount, int usedPassages,
                          String job, List<Problem> problems) {
        int total = JsonPath.read(job, "$.total");
        // 예외로 끝나 저장되지 않은 문항
        int errors = total - problems.size();
        Map<ValidationStatus, Long> byStatus = problems.stream()
                .collect(Collectors.groupingBy(Problem::getValidationStatus, () -> new EnumMap<>(ValidationStatus.class), Collectors.counting()));
        long passed = byStatus.getOrDefault(ValidationStatus.PASSED, 0L);
        long needsReview = byStatus.getOrDefault(ValidationStatus.NEEDS_REVIEW, 0L);
        long failed = byStatus.getOrDefault(ValidationStatus.FAILED, 0L);
        long firstTry = problems.stream().filter(p -> attempts(p) == 1 && p.getValidationStatus() == ValidationStatus.PASSED).count();
        // 검증이 없었다면 강사에게 그대로 갔을 첫 시도 결과 중, 규칙 검증 또는 블라인드 풀이에서 문제가 발견된 문항
        long caught = problems.stream().filter(p -> attempts(p) > 1 || p.getValidationStatus() != ValidationStatus.PASSED).count();

        StringBuilder md = new StringBuilder("# 전체 흐름 점검: " + exam + "\n\n");
        md.append("- 기출: ").append(exam).append(" / 시험범위: ").append(material).append('\n');
        md.append("- 지문 분리 ").append(splitCount).append("개 중 ").append(MIN_WORDS).append("단어 이상 긴 지문 ")
                .append(usedPassages).append("개 사용\n");
        md.append("- 강사 의견: \"").append(FEEDBACK).append("\"\n");
        md.append("- 지문당 유형 구성: v1 ").append(mixLabel(JsonPath.read(v1, "$.typeMixPerPassage")))
                .append(" → 확정 ").append(mixLabel(JsonPath.read(confirmed, "$.typeMixPerPassage"))).append('\n');
        List<String> changes = JsonPath.read(v2, "$.changeSummary");
        changes.forEach(c -> md.append("  - ").append(c).append('\n'));

        md.append("\n## 단계별 소요 시간\n\n| 단계 | 시간 |\n|---|---|\n");
        timings.forEach((step, ms) -> md.append("| ").append(step).append(" | ").append(String.format("%.1f초", ms / 1000.0)).append(" |\n"));

        md.append("\n## 1. 규칙 검증 통과율\n\n| 생성 | 첫 시도 통과 | 최종 통과(PASSED) | 확인 필요 | 검증 실패 | 오류 |\n|---|---|---|---|---|---|\n");
        md.append("| ").append(total).append(" | ").append(firstTry).append(" | ").append(passed).append(" | ").append(needsReview)
                .append(" | ").append(failed).append(" | ").append(errors).append(" |\n\n");
        md.append("- 최종 규칙 검증 통과율: ").append(percent(passed + needsReview, total))
                .append(" (확인 필요는 규칙은 통과, 블라인드 풀이만 불일치)\n");
        md.append("- 바로 쓸 수 있는 문항(PASSED): ").append(percent(passed, total)).append('\n');

        md.append("\n## 2. 검증 적용 전 vs 후\n\n");
        md.append("- 적용 전: 첫 시도 결과 중 규칙 검증 또는 블라인드 풀이에서 문제가 발견된 문항 ").append(caught).append("/").append(total)
                .append(" (").append(percent(caught, total)).append(")\n");
        md.append("- 적용 후: 강사에게 '통과'로 전달되는 문항 중 문제가 남은 문항 = 아래 검수표에서 사람이 찾은 정답 오류 수 / ").append(passed)
                .append("\n- 확인 필요·검증 실패 문항(").append(needsReview + failed).append("개)은 화면에서 따로 표시되어 강사가 먼저 확인합니다.\n");
        Map<String, Long> reasons = problems.stream()
                .flatMap(p -> p.getValidationReport() == null ? Stream.empty() : p.getValidationReport().attemptFailures().stream())
                .map(EndToEndExperiment::reasonKey)
                .collect(Collectors.groupingBy(r -> r, LinkedHashMap::new, Collectors.counting()));
        if (!reasons.isEmpty()) {
            md.append("\n재생성 이유 (실패한 시도 기준):\n\n");
            reasons.entrySet().stream().sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                    .forEach(e -> md.append("- ").append(e.getKey()).append(": ").append(e.getValue()).append("회\n"));
        }

        md.append("\n## 3. 유형 구성 일치도\n\n| 유형 | 계획 | 생성(오류 제외) | 규칙 통과 |\n|---|---|---|---|\n");
        Map<String, Integer> mix = JsonPath.read(confirmed, "$.typeMixPerPassage");
        long planned = 0;
        long matched = 0;
        for (Map.Entry<String, Integer> e : mix.entrySet()) {
            QuestionType type = QuestionType.valueOf(e.getKey());
            long plan = (long) e.getValue() * usedPassages;
            long made = problems.stream().filter(p -> p.getType() == type).count();
            long usable = problems.stream().filter(p -> p.getType() == type && p.getValidationStatus() != ValidationStatus.FAILED).count();
            planned += plan;
            matched += Math.min(plan, usable);
            md.append("| ").append(type.getLabel()).append(" | ").append(plan).append(" | ").append(made).append(" | ").append(usable).append(" |\n");
        }
        md.append("\n- 일치도(유형별 min(계획, 규칙 통과) 합 / 계획): ").append(percent(matched, planned)).append('\n');

        md.append("\n## 검수표\n\n'정답 오류' 칸에 O(정답이 틀림·복수 정답)/X를 적습니다.\n\n| # | 유형 | 지문 | 검증 | 시도 | 정답 오류 |\n|---|---|---|---|---|---|\n");
        for (int i = 0; i < problems.size(); i++) {
            Problem p = problems.get(i);
            md.append("| ").append(i + 1).append(" | ").append(p.getType().getLabel()).append(" | ").append(p.getPassageId())
                    .append(" | ").append(p.getValidationStatus()).append(" | ").append(attempts(p)).append(" |  |\n");
        }
        for (int i = 0; i < problems.size(); i++) {
            md.append(render(i + 1, problems.get(i)));
        }
        return md.toString();
    }

    private static String render(int no, Problem p) {
        StringBuilder md = new StringBuilder("\n---\n\n### ").append(no).append(". ").append(p.getType().getLabel()).append(" · ")
                .append(p.getValidationStatus()).append(" · 시도 ").append(attempts(p)).append("회\n\n");
        md.append("지문 ").append(p.getPassageId()).append(p.getPassageTitle() == null ? "" : " · " + p.getPassageTitle()).append("\n\n");
        md.append("**").append(p.getStem()).append("**\n\n");
        if (!p.getConditions().isEmpty()) {
            md.append("[조건]\n");
            p.getConditions().forEach(c -> md.append("- ").append(c).append('\n'));
        }
        if (p.getBody() != null) {
            md.append("\n> ").append(p.getBody().replace("\n", "\n> ")).append('\n');
        }
        if (!p.getChoices().isEmpty()) {
            md.append("\n[보기] ").append(String.join(" / ", p.getChoices())).append('\n');
        }
        md.append("\n정답: ").append(p.getAnswerText()).append("  \n해설: ").append(p.getExplanation()).append('\n');
        if (p.getValidationReport() != null) {
            p.getValidationReport().checks().stream().filter(c -> !c.passed())
                    .forEach(c -> md.append("\n- 검증: ").append(c.detail()));
            p.getValidationReport().attemptFailures().forEach(f -> md.append("\n- 재생성 이유: ").append(f));
            md.append('\n');
        }
        return md.toString();
    }

    /** "BLANK_COUNT: ..." 같은 실패 이유에서 앞부분만 모아 종류별로 센다 */
    private static String reasonKey(String failure) {
        String key = failure.strip();
        int cut = key.indexOf(':');
        if (cut > 0 && cut < 40) {
            return key.substring(0, cut);
        }
        return key.length() > 60 ? key.substring(0, 60) + "…" : key;
    }

    private static int attempts(Problem p) {
        return p.getValidationReport() == null ? 0 : p.getValidationReport().attempts();
    }

    private static String mixLabel(Map<String, Integer> mix) {
        return mix.entrySet().stream().map(e -> QuestionType.valueOf(e.getKey()).getLabel() + " " + e.getValue())
                .collect(Collectors.joining(", "));
    }

    private static String percent(long part, long whole) {
        return whole == 0 ? "-" : String.format("%.0f%% (%d/%d)", part * 100.0 / whole, part, whole);
    }

    private String poll(String url, String runningStatus, long intervalMs, int maxTries) throws Exception {
        String body = null;
        for (int i = 0; i < maxTries; i++) {
            body = mockMvc.perform(get(url)).andReturn().getResponse().getContentAsString();
            if (!runningStatus.equals(JsonPath.read(body, "$.status"))) {
                return body;
            }
            Thread.sleep(intervalMs);
        }
        return body;
    }

    private void lap(String step, long started) {
        long ms = System.currentTimeMillis() - started;
        timings.put(step, ms);
        System.out.println(step + ": " + ms + "ms");
    }

    private static long id(String json) {
        return ((Number) JsonPath.read(json, "$.id")).longValue();
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value.strip();
    }

    private static Path pick(String filter) throws IOException {
        if (!Files.isDirectory(INPUT_DIR)) {
            return null;
        }
        try (Stream<Path> files = Files.list(INPUT_DIR)) {
            return files.filter(p -> p.toString().toLowerCase().endsWith(".pdf"))
                    .filter(p -> p.getFileName().toString().contains(filter))
                    .sorted().findFirst().orElse(null);
        }
    }
}
