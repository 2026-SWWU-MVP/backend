package com.smwu.backend.profile.service;

import com.smwu.backend.ai.LlmClient;
import com.smwu.backend.ai.OpenAiLlmClient;
import com.smwu.backend.ai.prompt.PromptLoader;
import com.smwu.backend.pastexam.domain.ExamType;
import com.smwu.backend.pastexam.domain.PastExam;
import com.smwu.backend.pastexam.domain.PastPassage;
import com.smwu.backend.pastexam.domain.PastQuestion;
import com.smwu.backend.pastexam.extraction.ExtractedExam;
import com.smwu.backend.profile.domain.ProfileOrigin;
import com.smwu.backend.profile.domain.ProfileRule;
import com.smwu.backend.profile.domain.ProfileRule.RuleSource;
import com.smwu.backend.profile.domain.ProfileStats;
import com.smwu.backend.profile.domain.SchoolProfile;
import com.smwu.backend.profile.domain.TeacherNote;
import com.smwu.backend.profile.service.ProfileDiff.Snapshot;
import com.smwu.backend.profile.service.ProfileSourceLoader.Source;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 출제 프로필 강사 검토 실험 (이슈 #13). 실제 OpenAI를 호출한다 (비용 발생).
 * 저장된 기출 추출 결과 1개로 프로필을 만들고 → 강사 의견 반영 → AI 재검토를 차례로 해서 변경점을 확인한다.
 * <pre>
 * EXPERIMENT_FILTER=압구정 ./gradlew llmTest --tests '*ProfileReviewExperiment'
 * </pre>
 * 결과: build/experiments/profile/review-{시험지}.md
 */
@Tag("llm")
@EnabledIfEnvironmentVariable(named = "LLM_API_KEY", matches = ".+")
@EnabledIfEnvironmentVariable(named = "LLM_MODEL", matches = ".+")
class ProfileReviewExperiment {

    private static final Path INPUT_DIR = Path.of("build", "experiments", "extract");
    private static final Path OUTPUT_DIR = Path.of("build", "experiments", "profile");
    private static final String NOTE = "학생들 말로는 이번 서술형은 어구 배열 위주로 낸다고 하심. 요약문 빈칸은 1문제 정도만 나온대요.";

    private final JsonMapper objectMapper = JsonMapper.builder().build();

    @Test
    void 프로필_생성_강사_의견_반영_AI_재검토() throws IOException {
        Path json = pickJson();
        Assumptions.assumeTrue(json != null, "build/experiments/extract/*.json이 없어서 건너뜀");
        String name = json.getFileName().toString().replaceFirst("\\.json$", "");
        Files.createDirectories(OUTPUT_DIR);

        LlmClient llm = new OpenAiLlmClient(OpenAiLlmClient.restClientBuilder(Duration.ofSeconds(180)), objectMapper,
                "https://api.openai.com/v1", System.getenv("LLM_API_KEY"), System.getenv("LLM_MODEL"), Duration.ofSeconds(2));
        PromptLoader prompts = new PromptLoader(objectMapper);
        Source source = load(json, name);

        // v1: 기출 분석
        ProfileStats stats = ProfileStatsCalculator.calculate(1, source.questions());
        RuleSummarizer.Result summary = new RuleSummarizer(llm, prompts)
                .summarize(source.target(), stats, source.exams(), source.subjectiveQuestions(), source.passagesByExam());
        SchoolProfile v1 = SchoolProfile.builder().workspaceId(1L).version(1).origin(ProfileOrigin.INITIAL_ANALYSIS)
                .stats(stats).rules(summary.rules()).typeMixPerPassage(ProfileStatsCalculator.typeMixPerPassage(source.questions()))
                .exampleQuestionIds(summary.exampleQuestionIds()).sourceExamIds(List.of(1L)).build();
        ReflectionTestUtils.setField(v1, "id", 1L);

        // v2: 강사 의견
        long startedAt = System.currentTimeMillis();
        FeedbackApplier.Result feedback = new FeedbackApplier(llm, prompts).apply(v1, NOTE);
        long feedbackMs = System.currentTimeMillis() - startedAt;
        List<TeacherNote> notes = List.of(new TeacherNote(NOTE, false, null));
        List<ProfileRule> v2Rules = new ArrayList<>();
        for (ProfileRule r : v1.getRules()) {
            v2Rules.add(feedback.overrideIds().contains(r.id())
                    ? new ProfileRule(r.id(), r.text(), r.category(), r.source(), r.evidenceQuestionIds(), true, null) : r);
        }
        feedback.newRules().forEach(n -> v2Rules.add(new ProfileRule(ProfileRules.nextRuleId(v2Rules), n.text(), n.category(),
                RuleSource.TEACHER, List.of(), false, 0)));
        SchoolProfile v2 = v1.revise(2, ProfileOrigin.TEACHER_FEEDBACK, v2Rules, feedback.typeMix(), notes,
                v1.getExampleQuestionIds(), List.of(), feedback.model(), null);
        ReflectionTestUtils.setField(v2, "id", 2L);

        // v3: AI 재검토
        startedAt = System.currentTimeMillis();
        ProfileRechecker.Result recheck = new ProfileRechecker(llm, prompts).recheck(v2, source);
        long recheckMs = System.currentTimeMillis() - startedAt;

        StringBuilder md = new StringBuilder("# " + name + " 출제 프로필 검토 실험\n\n");
        md.append("## v1 기출 분석\n\n").append(ProfileRules.describeRules(v1.getRules(), null)).append("\n\n");
        md.append("지문당 유형 구성: ").append(ProfileRules.describeTypeMix(v1.getTypeMixPerPassage())).append("\n\n");
        md.append("## v2 강사 의견 반영 (").append(feedbackMs / 1000.0).append("초)\n\n> ").append(NOTE).append("\n\n");
        md.append("변경점:\n");
        ProfileDiff.describe(snapshot(v1), new Snapshot(v2Rules, feedback.typeMix(), notes, v1.getExampleQuestionIds()))
                .forEach(l -> md.append("- ").append(l).append('\n'));
        md.append("\n## v3 AI 재검토 (").append(recheckMs / 1000.0).append("초)\n\n변경점:\n");
        ProfileDiff.describe(snapshot(v2), new Snapshot(recheck.rules(), v2.getTypeMixPerPassage(), notes, recheck.exampleQuestionIds()))
                .forEach(l -> md.append("- ").append(l).append('\n'));
        md.append("\n최종 규칙:\n\n").append(ProfileRules.describeRules(recheck.rules(), null)).append('\n');
        Files.writeString(OUTPUT_DIR.resolve("review-" + name + ".md"), md.toString(), StandardCharsets.UTF_8);
        System.out.println(md);
    }

    private static Snapshot snapshot(SchoolProfile p) {
        return new Snapshot(p.getRules(), p.getTypeMixPerPassage(), p.getTeacherNotes(), p.getExampleQuestionIds());
    }

    private Source load(Path json, String name) throws IOException {
        ExtractedExam extracted = objectMapper.treeToValue(
                objectMapper.readTree(Files.readString(json, StandardCharsets.UTF_8)).path("result"), ExtractedExam.class);
        PastExam exam = PastExam.builder().workspaceId(1L).examYear(2025).semester(1).examType(ExamType.MIDTERM)
                .originalFilename(name + ".pdf").filePath("x").fileSize(1).pageCount(1).textLayer(true).build();
        ReflectionTestUtils.setField(exam, "id", 1L);
        List<PastQuestion> questions = new ArrayList<>();
        for (int i = 0; i < extracted.questions().size(); i++) {
            ExtractedExam.Question q = extracted.questions().get(i);
            PastQuestion pq = PastQuestion.builder().pastExamId(1L).orderNo(i + 1).section(q.section()).no(q.no())
                    .type(q.type()).passageCodes(q.passageIds()).stem(q.stem()).body(q.body()).conditions(q.conditions())
                    .choices(q.choices()).answer(q.answer()).points(q.points()).build();
            ReflectionTestUtils.setField(pq, "id", (long) (i + 1));
            questions.add(pq);
        }
        List<PastPassage> passages = extracted.passages().stream()
                .map(p -> new PastPassage(1L, p.id(), 0, p.title(), p.text())).toList();
        return new Source(List.of(exam), questions, Map.of(1L, passages));
    }

    private static Path pickJson() throws IOException {
        if (!Files.isDirectory(INPUT_DIR)) {
            return null;
        }
        String filter = System.getenv().getOrDefault("EXPERIMENT_FILTER", "");
        try (Stream<Path> files = Files.list(INPUT_DIR)) {
            return files.filter(p -> p.toString().endsWith(".json"))
                    .filter(p -> filter.isBlank() || p.getFileName().toString().contains(filter.strip()))
                    .sorted().findFirst().orElse(null);
        }
    }
}
