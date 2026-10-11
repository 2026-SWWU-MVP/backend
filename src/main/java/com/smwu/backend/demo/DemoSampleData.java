package com.smwu.backend.demo;

import com.smwu.backend.common.storage.FileStorage;
import com.smwu.backend.generation.domain.GenerationJob;
import com.smwu.backend.generation.domain.GenerationPlan;
import com.smwu.backend.generation.domain.GenerationPlan.TypeCount;
import com.smwu.backend.generation.repository.GenerationJobRepository;
import com.smwu.backend.material.domain.Passage;
import com.smwu.backend.pastexam.domain.ExamType;
import com.smwu.backend.pastexam.domain.PastExam;
import com.smwu.backend.pastexam.domain.PastPassage;
import com.smwu.backend.pastexam.domain.PastQuestion;
import com.smwu.backend.pastexam.event.PastExamChangedEvent;
import com.smwu.backend.pastexam.extraction.ExtractedExam.QuestionSection;
import com.smwu.backend.pastexam.extraction.QuestionType;
import com.smwu.backend.pastexam.repository.PastExamRepository;
import com.smwu.backend.pastexam.repository.PastPassageRepository;
import com.smwu.backend.pastexam.repository.PastQuestionRepository;
import com.smwu.backend.problem.domain.Problem;
import com.smwu.backend.problem.domain.ProblemReviewLog;
import com.smwu.backend.problem.domain.ProblemReviewLog.Action;
import com.smwu.backend.problem.domain.ProblemReviewLog.FieldChange;
import com.smwu.backend.problem.domain.ReviewStatus;
import com.smwu.backend.problem.domain.ValidationReport;
import com.smwu.backend.problem.domain.ValidationStatus;
import com.smwu.backend.problem.repository.ProblemRepository;
import com.smwu.backend.problem.repository.ProblemReviewLogRepository;
import com.smwu.backend.problem.type.AssembledProblem;
import com.smwu.backend.problem.type.GrammarFixHandler;
import com.smwu.backend.problem.type.PassageSource;
import com.smwu.backend.problem.type.ProblemOptions;
import com.smwu.backend.problem.type.SentenceOrderHandler;
import com.smwu.backend.problem.type.SummaryBlankHandler;
import com.smwu.backend.problem.type.ValidationCheck;
import com.smwu.backend.profile.domain.ProfileOrigin;
import com.smwu.backend.profile.domain.ProfileRule;
import com.smwu.backend.profile.domain.ProfileRule.RuleCategory;
import com.smwu.backend.profile.domain.ProfileRule.RuleSource;
import com.smwu.backend.profile.domain.ProfileStats;
import com.smwu.backend.profile.domain.SchoolProfile;
import com.smwu.backend.profile.domain.TeacherNote;
import com.smwu.backend.profile.repository.SchoolProfileRepository;
import com.smwu.backend.profile.service.ProfileStatsCalculator;
import com.smwu.backend.user.domain.User;
import com.smwu.backend.workspace.domain.Workspace;
import com.smwu.backend.worksheet.domain.Worksheet;
import com.smwu.backend.worksheet.domain.WorksheetItem;
import com.smwu.backend.worksheet.repository.WorksheetItemRepository;
import com.smwu.backend.worksheet.repository.WorksheetRepository;
import lombok.RequiredArgsConstructor;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 모든 화면에 데이터가 보이도록 만드는 시연용 더미 데이터 (#62). LLM을 부르지 않고 엔티티를 직접 만든다.
 * <ul>
 *   <li>압구정고 1학년: 추출 완료 기출 3회분(+ 다른 학원 1회분 → 학교 DB 4회분), 프로필 v1(대체됨)·v2(확정),
 *       생성 작업 2개(채택·폐기·수정·확인 필요), 시험지 1개 — "다 끝난" 워크스페이스</li>
 *   <li>현대고 2학년: 기출 1회분 + 프로필 v1 초안 — "검토 중" 워크스페이스</li>
 * </ul>
 * 기출 지문·문항은 시연용으로 새로 쓴 글이다 (실제 학교 기출 아님).
 */
@Component
@RequiredArgsConstructor
class DemoSampleData {

    static final String LLM_MODEL = "demo-seed";

    private final PastExamRepository pastExamRepository;
    private final PastPassageRepository pastPassageRepository;
    private final PastQuestionRepository pastQuestionRepository;
    private final SchoolProfileRepository profileRepository;
    private final GenerationJobRepository jobRepository;
    private final ProblemRepository problemRepository;
    private final ProblemReviewLogRepository reviewLogRepository;
    private final WorksheetRepository worksheetRepository;
    private final WorksheetItemRepository worksheetItemRepository;
    private final SummaryBlankHandler summaryBlank;
    private final SentenceOrderHandler sentenceOrder;
    private final GrammarFixHandler grammarFix;
    private final FileStorage fileStorage;
    private final ApplicationEventPublisher eventPublisher;

    /** 다른 학원이 같은 학교·학년에 올린 기출. 학교 DB(경향)에는 통계만 반영되고 원문은 보이지 않는다 */
    void otherAcademy(Workspace workspace, User owner) {
        pastExam(workspace, owner, OTHER_2023_FINAL);
    }

    /** 압구정고 1학년: 기출 → 프로필 확정 → 생성 → 검수 → 시험지까지 끝난 상태 */
    void completed(Workspace workspace, User owner, User teacher, List<Passage> textbook) {
        List<PastQuestion> questions = new ArrayList<>();
        List<Long> examIds = new ArrayList<>();
        for (ExamSpec spec : List.of(OWN_2024_MIDTERM, OWN_2024_FINAL, OWN_2025_MIDTERM)) {
            Seeded seeded = pastExam(workspace, teacher, spec);
            examIds.add(seeded.exam().getId());
            questions.addAll(seeded.questions());
        }
        SchoolProfile confirmed = profiles(workspace, owner, teacher, examIds, questions);
        generated(workspace, teacher, confirmed, textbook);
    }

    /** 현대고 2학년: 기출 1회분을 분석한 프로필 초안 (아직 확정 전) */
    void inReview(Workspace workspace, User teacher) {
        Seeded seeded = pastExam(workspace, teacher, HYUNDAI_2025_MIDTERM);
        List<PastQuestion> subjective = seeded.questions().stream().filter(q -> q.getSection() == QuestionSection.SUBJECTIVE).toList();
        profileRepository.save(SchoolProfile.builder()
                .workspaceId(workspace.getId())
                .version(1)
                .origin(ProfileOrigin.INITIAL_ANALYSIS)
                .stats(ProfileStatsCalculator.calculate(1, seeded.questions()))
                .rules(List.of(
                        rule("r1", "조건 영작은 지문 핵심 문장을 우리말로 주고 제시어를 모두 쓰게 한다", RuleCategory.QUESTION_TYPE,
                                ids(subjective, QuestionType.GUIDED_WRITING)),
                        rule("r2", "요약문 빈칸은 본문 단어를 형태 변경 없이 쓰게 한다", RuleCategory.CONDITION,
                                ids(subjective, QuestionType.SUMMARY_BLANK)),
                        rule("r3", "단답형은 지문의 세부 내용을 영어 한 단어로 묻는다", RuleCategory.PASSAGE_USE,
                                ids(subjective, QuestionType.SUBJ_SHORT_ANSWER))))
                .typeMixPerPassage(mix(QuestionType.SUMMARY_BLANK, 1, QuestionType.GUIDED_WRITING, 1))
                .changeSummary(List.of("기출 1개(2025년 1학기 중간)로 첫 출제 프로필을 만들었습니다."))
                .exampleQuestionIds(subjective.stream().map(PastQuestion::getId).toList())
                .sourceExamIds(List.of(seeded.exam().getId()))
                .llmModel(LLM_MODEL)
                .createdBy(teacher.getId())
                .build());
    }

    // ===== 기출 =====

    private record Seeded(PastExam exam, List<PastQuestion> questions) {
    }

    private Seeded pastExam(Workspace workspace, User uploader, ExamSpec spec) {
        String filename = spec.year() + "_" + spec.semester() + "학기_" + (spec.type() == ExamType.MIDTERM ? "중간" : "기말") + ".pdf";
        byte[] pdf = blankPdf();
        PastExam exam = pastExamRepository.save(PastExam.builder()
                .workspaceId(workspace.getId())
                .examYear(spec.year())
                .semester(spec.semester())
                .examType(spec.type())
                .originalFilename(filename)
                .filePath(fileStorage.save("past-exams", "pdf", pdf))
                .fileSize(pdf.length)
                .pageCount(1)
                .textLayer(true)
                .createdBy(uploader.getId())
                .build());
        for (int i = 0; i < spec.passages().size(); i++) {
            DemoPassage p = spec.passages().get(i);
            pastPassageRepository.save(new PastPassage(exam.getId(), "P" + (i + 1), i + 1, p.title(), p.text()));
        }
        List<PastQuestion> questions = new ArrayList<>();
        int order = 1;
        int objectiveNo = 1;
        for (int i = 0; i < spec.passages().size(); i++) {
            for (Q q : objectives(spec.passages().get(i), "P" + (i + 1))) {
                questions.add(question(exam, order++, QuestionSection.OBJECTIVE, objectiveNo++, q));
            }
        }
        int subjectiveNo = 1;
        for (Q q : spec.subjective()) {
            questions.add(question(exam, order++, QuestionSection.SUBJECTIVE, subjectiveNo++, q));
        }
        exam.completeExtraction(spec.warnings(), LLM_MODEL, 0, 0, 0, null);
        pastExamRepository.save(exam);
        // 학교 DB 반영 (SchoolExamContributionService가 같은 트랜잭션에서 처리)
        eventPublisher.publishEvent(new PastExamChangedEvent(exam.getId()));
        return new Seeded(exam, questions);
    }

    private PastQuestion question(PastExam exam, int order, QuestionSection section, int no, Q q) {
        return pastQuestionRepository.save(PastQuestion.builder()
                .pastExamId(exam.getId())
                .orderNo(order)
                .section(section)
                .no(no)
                .type(q.type())
                .passageCodes(List.of(q.passageCode()))
                .stem(q.stem())
                .body(q.body())
                .conditions(q.conditions())
                .choices(q.choices())
                .answer(q.answer())
                .points(q.points())
                .build());
    }

    /** 지문마다 객관식 3문항 (주제·빈칸 추론·어법) */
    private static List<Q> objectives(DemoPassage p, String code) {
        return List.of(
                new Q(QuestionType.OBJ_MAIN_IDEA, code, "다음 글의 주제로 가장 적절한 것은?", null, List.of(), p.topics(), p.topicAnswer(), 3.0),
                new Q(QuestionType.OBJ_BLANK, code, "다음 글의 빈칸에 들어갈 말로 가장 적절한 것은?", null, List.of(), p.blanks(), "②", 3.5),
                new Q(QuestionType.OBJ_GRAMMAR, code, "다음 글의 밑줄 친 부분 중, 어법상 틀린 것은?", null, List.of(),
                        List.of("①", "②", "③", "④", "⑤"), "④", 3.0));
    }

    private static byte[] blankPdf() {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            document.addPage(new PDPage());
            document.save(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("데모 기출 PDF를 만들지 못했습니다.", e);
        }
    }

    // ===== 출제 프로필 =====

    /** v1 기출 분석 → v2 강사 의견 반영·확정 (v1은 대체됨) */
    private SchoolProfile profiles(Workspace workspace, User owner, User teacher, List<Long> examIds, List<PastQuestion> questions) {
        List<PastQuestion> subjective = questions.stream().filter(q -> q.getSection() == QuestionSection.SUBJECTIVE).toList();
        ProfileStats own = ProfileStatsCalculator.calculate(examIds.size(), questions);
        ProfileStats stats = new ProfileStats(own.examCount(), own.totalQuestions(), own.objectiveCount(), own.subjectiveCount(),
                own.subjectiveRatio(), own.subjectivePointsRatio(), own.typeCounts(), own.frequentConditions(), 1);
        List<ProfileRule> rules = List.of(
                rule("r1", "요약문 빈칸은 매 시험 지문 하나에서 출제된다", RuleCategory.QUESTION_TYPE,
                        ids(subjective, QuestionType.SUMMARY_BLANK)),
                rule("r2", "어구 배열은 매 시험 출제되고, 최근 시험에서는 2문항으로 늘었다", RuleCategory.QUESTION_TYPE,
                        ids(subjective, QuestionType.SENTENCE_ORDER)),
                rule("r3", "어구 배열은 [보기]의 어구를 모두 한 번씩 쓰고 형태를 바꾸지 않는다", RuleCategory.CONDITION,
                        ids(subjective, QuestionType.SENTENCE_ORDER)),
                rule("r4", "요약문 빈칸은 첫 글자를 주고 본문에 나온 단어를 그대로 쓰게 한다", RuleCategory.CONDITION,
                        ids(subjective, QuestionType.SUMMARY_BLANK)),
                rule("r5", "어법 오류 수정은 분사구문과 to부정사의 쓰임을 묻는다", RuleCategory.GRAMMAR_POINT,
                        ids(subjective, QuestionType.GRAMMAR_FIX)),
                rule("r6", "서술형은 문항당 4~6점이고, 서술형 배점 비중이 30%를 넘는다", RuleCategory.SCORING,
                        subjective.stream().limit(3).map(PastQuestion::getId).toList()),
                new ProfileRule("r7", "조건 영작이 꾸준히 출제된다 (다른 학원 기출 포함)", RuleCategory.QUESTION_TYPE,
                        RuleSource.SCHOOL_DB, List.of(), false, null));
        List<Long> examples = List.of(first(subjective, QuestionType.SUMMARY_BLANK), first(subjective, QuestionType.SENTENCE_ORDER),
                first(subjective, QuestionType.GRAMMAR_FIX));

        SchoolProfile v1 = SchoolProfile.builder()
                .workspaceId(workspace.getId())
                .version(1)
                .origin(ProfileOrigin.INITIAL_ANALYSIS)
                .stats(stats)
                .rules(rules)
                .typeMixPerPassage(mix(QuestionType.SUMMARY_BLANK, 1, QuestionType.SENTENCE_ORDER, 1, QuestionType.GUIDED_WRITING, 1))
                .changeSummary(List.of("기출 3개(2024년 1학기 중간, 2024년 2학기 기말, 2025년 1학기 중간)로 첫 출제 프로필을 만들었습니다.",
                        "학교 DB 기출 1회분(다른 학원 기출, 우리 기출과 같은 회차 제외)의 경향을 함께 반영했습니다."))
                .exampleQuestionIds(examples)
                .sourceExamIds(examIds)
                .llmModel(LLM_MODEL)
                .createdBy(teacher.getId())
                .build();
        v1.confirm(teacher.getId());
        v1 = profileRepository.save(v1);

        List<ProfileRule> revised = new ArrayList<>();
        for (ProfileRule r : rules) {
            revised.add(r.id().equals("r7")
                    ? new ProfileRule(r.id(), r.text(), r.category(), r.source(), r.evidenceQuestionIds(), true, r.noteIndex())
                    : r);
        }
        revised.add(new ProfileRule("r8", "올해는 조건 영작 대신 어법 오류 수정을 낸다", RuleCategory.QUESTION_TYPE,
                RuleSource.TEACHER, List.of(), false, 0));
        SchoolProfile v2 = v1.revise(2, ProfileOrigin.TEACHER_FEEDBACK, revised,
                mix(QuestionType.SUMMARY_BLANK, 1, QuestionType.SENTENCE_ORDER, 1, QuestionType.GRAMMAR_FIX, 1),
                List.of(new TeacherNote("올해 1학기 중간은 조건 영작 대신 어법 오류 수정을 낸다고 함 (학생 설명회)", false, teacher.getId())),
                examples,
                List.of("강사 의견 반영: 올해는 조건 영작 대신 어법 오류 수정",
                        "지문당 유형 구성: 어법 오류 수정 0 → 1, 조건 영작 1 → 0",
                        "[학교 DB] r7 조건 영작 규칙은 이번 시험에 적용하지 않습니다."),
                LLM_MODEL, teacher.getId());
        v1.supersede();
        v2.confirm(owner.getId());
        return profileRepository.save(v2);
    }

    private static ProfileRule rule(String id, String text, RuleCategory category, List<Long> evidence) {
        return new ProfileRule(id, text, category, RuleSource.PAST_EXAM, evidence, false, null);
    }

    private static List<Long> ids(List<PastQuestion> questions, QuestionType type) {
        return questions.stream().filter(q -> q.getType() == type).map(PastQuestion::getId).toList();
    }

    private static Long first(List<PastQuestion> questions, QuestionType type) {
        return ids(questions, type).get(0);
    }

    private static Map<QuestionType, Integer> mix(Object... typeAndCount) {
        Map<QuestionType, Integer> mix = new LinkedHashMap<>();
        for (int i = 0; i < typeAndCount.length; i += 2) {
            mix.put((QuestionType) typeAndCount[i], (Integer) typeAndCount[i + 1]);
        }
        return mix;
    }

    // ===== 생성 · 검수 · 시험지 =====

    private static final ProblemOptions SUMMARY_OPTIONS = new ProblemOptions(2, true, null, null).withDefaults();
    private static final ProblemOptions ORDER_OPTIONS = new ProblemOptions(null, null, 12, null).withDefaults();
    private static final ProblemOptions GRAMMAR_OPTIONS = new ProblemOptions(null, null, null, 1).withDefaults();

    /** 작업 1(요약문 빈칸만, 채택률 50%) → 작업 2(3유형, 채택률 80%): "쓸수록 맞춤"이 리포트에 보이도록 */
    private void generated(Workspace workspace, User teacher, SchoolProfile profile, List<Passage> textbook) {
        PassageSource cities = textbook.get(0).toSource();
        PassageSource crime = textbook.get(1).toSource();
        List<Long> passageIds = textbook.stream().map(Passage::getId).toList();

        GenerationJob first = jobRepository.save(GenerationJob.start(workspace.getId(), profile.getId(),
                new GenerationPlan(passageIds, List.of(new TypeCount(QuestionType.SUMMARY_BLANK, 1, SUMMARY_OPTIONS))), teacher.getId()));
        Problem firstCities = problem(workspace, profile, first, 0, cities, summaryBlank(cities, CITIES_SUMMARY, 1L));
        Problem firstCrime = problem(workspace, profile, first, 1, crime, summaryBlank(crime, CRIME_SUMMARY_EASY, 2L));
        accept(firstCities, teacher);
        reject(firstCrime, teacher, "요약문이 본문 문장을 거의 그대로 옮겨서 너무 쉬움");

        GenerationJob second = jobRepository.save(GenerationJob.start(workspace.getId(), profile.getId(),
                new GenerationPlan(passageIds, List.of(new TypeCount(QuestionType.SUMMARY_BLANK, 1, SUMMARY_OPTIONS),
                        new TypeCount(QuestionType.SENTENCE_ORDER, 1, ORDER_OPTIONS),
                        new TypeCount(QuestionType.GRAMMAR_FIX, 1, GRAMMAR_OPTIONS))), teacher.getId()));
        Problem citiesSummary = problem(workspace, profile, second, 0, cities, summaryBlank(cities, CITIES_SUMMARY, 3L));
        Problem citiesOrder = problem(workspace, profile, second, 1, cities, sentenceOrder(cities, CITIES_ORDER, 4L));
        Problem citiesGrammar = problem(workspace, profile, second, 2, cities, grammarFix(cities, CITIES_GRAMMAR, 5L));
        Problem crimeSummary = problem(workspace, profile, second, 3, crime,
                needsReview(summaryBlank(crime, CRIME_SUMMARY, 6L), "AI 풀이 답 (2) money가 정답 budget과 다르다. 정답이 하나로 정해지는지 확인해야 한다."));
        Problem crimeOrder = problem(workspace, profile, second, 4, crime, sentenceOrder(crime, CRIME_ORDER, 7L));
        Problem crimeGrammar = problem(workspace, profile, second, 5, crime, grammarFix(crime, CRIME_GRAMMAR, 8L));

        String before = citiesSummary.getExplanation();
        String after = before + " 두 빈칸 모두 첫 글자가 주어지므로 철자까지 정확히 써야 한다.";
        citiesSummary.edit(null, null, null, null, null, after, teacher.getId());
        reviewLogRepository.save(new ProblemReviewLog(citiesSummary, Action.EDITED, null,
                List.of(new FieldChange("explanation", before, after)), teacher.getId()));
        accept(citiesSummary, teacher);
        accept(citiesOrder, teacher);
        accept(citiesGrammar, teacher);
        accept(crimeOrder, teacher);
        reject(crimeGrammar, teacher, "밑줄 친 오류가 문장 맨 앞이라 너무 눈에 띔");
        // crimeSummary는 확인 필요(NEEDS_REVIEW) 상태로 검수 전에 둔다

        Worksheet worksheet = worksheetRepository.save(new Worksheet(workspace.getId(), "압구정고1 2과 서술형 대비",
                "압구정고 1학년 1학기 중간고사 서술형 대비 - 교과서 2과", true, teacher.getId()));
        List<Problem> items = List.of(citiesSummary, citiesOrder, citiesGrammar, crimeOrder);
        for (int i = 0; i < items.size(); i++) {
            worksheetItemRepository.save(new WorksheetItem(worksheet.getId(), items.get(i).getId(), i + 1));
        }

        // 진행 카운터는 벌크 업데이트로만 올릴 수 있다 (영속성 컨텍스트가 비워지므로 마지막에 한다)
        jobRepository.flush();
        finish(first.getId(), 2, 0);
        finish(second.getId(), 6, 0);
    }

    private void finish(Long jobId, int total, int errors) {
        for (int i = 0; i < total; i++) {
            jobRepository.increment(jobId, i < errors ? 1 : 0);
        }
        GenerationJob job = jobRepository.findById(jobId).orElseThrow();
        job.finishIfDone();
        jobRepository.saveAndFlush(job);
    }

    private record Generated(AssembledProblem problem, ProblemOptions options, ValidationStatus status, ValidationReport report) {
    }

    private Generated summaryBlank(PassageSource passage, SummaryBlankHandler.Draft draft, long seed) {
        AssembledProblem p = summaryBlank.assemble(draft, passage, SUMMARY_OPTIONS, seed);
        return validated(p, SUMMARY_OPTIONS, summaryBlank.validate(p, passage, SUMMARY_OPTIONS));
    }

    private Generated sentenceOrder(PassageSource passage, SentenceOrderHandler.Draft draft, long seed) {
        AssembledProblem p = sentenceOrder.assemble(draft, passage, ORDER_OPTIONS, seed);
        return validated(p, ORDER_OPTIONS, sentenceOrder.validate(p, passage, ORDER_OPTIONS));
    }

    private Generated grammarFix(PassageSource passage, GrammarFixHandler.Draft draft, long seed) {
        AssembledProblem p = grammarFix.assemble(draft, passage, GRAMMAR_OPTIONS, seed);
        return validated(p, GRAMMAR_OPTIONS, grammarFix.validate(p, passage, GRAMMAR_OPTIONS));
    }

    private static Generated validated(AssembledProblem problem, ProblemOptions options, List<ValidationCheck> checks) {
        List<ValidationCheck> all = new ArrayList<>(checks);
        all.add(ValidationCheck.pass("BLIND_SOLVE"));
        boolean passed = all.stream().allMatch(ValidationCheck::passed);
        return new Generated(problem, options, passed ? ValidationStatus.PASSED : ValidationStatus.FAILED,
                new ValidationReport(all, 1, List.of()));
    }

    /** 규칙은 통과했지만 블라인드 풀이가 어긋난 문항 → 강사 확인 필요 */
    private static Generated needsReview(Generated generated, String detail) {
        List<ValidationCheck> checks = new ArrayList<>(generated.report().checks().stream()
                .filter(c -> !c.name().equals("BLIND_SOLVE")).toList());
        checks.add(ValidationCheck.fail("BLIND_SOLVE", detail));
        return new Generated(generated.problem(), generated.options(), ValidationStatus.NEEDS_REVIEW,
                new ValidationReport(checks, 2, List.of("1회차: 블라인드 풀이 불일치")));
    }

    private Problem problem(Workspace workspace, SchoolProfile profile, GenerationJob job, int slot, PassageSource passage,
                            Generated generated) {
        Problem problem = Problem.generated(workspace.getId(), profile.getId(), passage, job.getId(), generated.problem().type(),
                generated.options(), generated.problem(), generated.status(), generated.report(), LLM_MODEL);
        problem.assignJobSlot(slot);
        return problemRepository.save(problem);
    }

    private void accept(Problem problem, User teacher) {
        problem.review(ReviewStatus.ACCEPTED, teacher.getId());
        reviewLogRepository.save(new ProblemReviewLog(problem, Action.ACCEPTED, null, List.of(), teacher.getId()));
    }

    private void reject(Problem problem, User teacher, String reason) {
        problem.review(ReviewStatus.REJECTED, teacher.getId());
        reviewLogRepository.save(new ProblemReviewLog(problem, Action.REJECTED, reason, List.of(), teacher.getId()));
    }

    // 시험범위(교과서 2과) 지문으로 만든 문항 초안 — Mock LLM 응답과 같은 내용
    static final SummaryBlankHandler.Draft CITIES_SUMMARY = new SummaryBlankHandler.Draft(
            "As a neighborhood ages, it may become [[lifeless]] and lose residents. Cooperation between the local government and citizens can help [[revitalize]] the area.",
            "When this happens, a collaboration between the local government and citizens is an effective way to revitalize the area.",
            "(1) 도시가 오래되면 동네가 활기를 잃는다(lifeless)는 첫 문장에서, (2) 정부와 시민의 협력이 지역을 되살린다(revitalize)는 둘째 문장에서 찾을 수 있다.");
    static final SummaryBlankHandler.Draft CRIME_SUMMARY_EASY = new SummaryBlankHandler.Draft(
            "For years, the neighborhood was known for its high [[crime]] rates, and the government had a limited [[budget]].",
            "However, having a limited budget, the government was unable to do so and had to come up with a new plan.",
            "(1) 첫 문장의 high crime rates, (2) 둘째 문장의 a limited budget에서 그대로 찾을 수 있다.");
    static final SummaryBlankHandler.Draft CRIME_SUMMARY = new SummaryBlankHandler.Draft(
            "The neighborhood suffered from [[crime]] for years, but a lack of [[budget]] forced the government to find a different solution.",
            "However, having a limited budget, the government was unable to do so and had to come up with a new plan.",
            "(1) 오랫동안 범죄율이 높았다(crime)는 첫 문장, (2) 예산이 부족해(budget) 새 계획을 세워야 했다는 둘째 문장에서 찾는다.");
    static final SentenceOrderHandler.Draft CITIES_ORDER = new SentenceOrderHandler.Draft(
            "When this happens, a collaboration between the local government and citizens is an effective way to revitalize the area.",
            List.of("When this happens", "a collaboration", "between the local government", "and citizens", "is", "an effective way",
                    "to revitalize", "the area"),
            "a collaboration이 주어, is가 동사이고 between ~ citizens가 주어를 꾸민다. to revitalize the area는 an effective way를 꾸미는 to부정사다.");
    static final SentenceOrderHandler.Draft CRIME_ORDER = new SentenceOrderHandler.Draft(
            "However, having a limited budget, the government was unable to do so and had to come up with a new plan.",
            List.of("However", "having", "a limited budget", "the government", "was unable", "to do so", "and had to come up with",
                    "a new plan"),
            "having a limited budget은 이유를 나타내는 분사구문으로, 주절의 주어 the government 앞에 온다. was unable to do so와 had to come up with가 and로 병렬 연결된다.");
    static final GrammarFixHandler.Draft CITIES_GRAMMAR = new GrammarFixHandler.Draft(List.of(
            new GrammarFixHandler.Target("neighborhoods can", "become", "become"),
            new GrammarFixHandler.Target("may cause citizens", "to move", "moving"),
            new GrammarFixHandler.Target("government and citizens", "is", "is"),
            new GrammarFixHandler.Target("an effective way to", "revitalize", "revitalize"),
            new GrammarFixHandler.Target("Netherlands to find out", "what", "what")),
            "② cause는 목적격 보어로 to부정사를 쓰므로 moving을 to move로 고쳐야 한다. 나머지는 어법상 맞다.");
    static final GrammarFixHandler.Draft CRIME_GRAMMAR = new GrammarFixHandler.Draft(List.of(
            new GrammarFixHandler.Target("For years, the neighborhood", "was", "were"),
            new GrammarFixHandler.Target("known", "for", "for"),
            new GrammarFixHandler.Target("However,", "having", "having"),
            new GrammarFixHandler.Target("the government was", "unable", "unable"),
            new GrammarFixHandler.Target("had to", "come up with", "come up with")),
            "① 주어 the neighborhood가 단수이므로 were가 아니라 was를 써야 한다.");

    // ===== 기출 데이터 (시연용으로 새로 쓴 글) =====

    private record DemoPassage(String title, String text, List<String> topics, String topicAnswer, List<String> blanks) {
    }

    private record Q(QuestionType type, String passageCode, String stem, String body, List<String> conditions, List<String> choices,
                     String answer, Double points) {
    }

    private record ExamSpec(int year, int semester, ExamType type, List<DemoPassage> passages, List<Q> subjective,
                            List<String> warnings) {
    }

    private static Q subjective(QuestionType type, String code, String stem, String body, List<String> conditions, String answer,
                                double points) {
        return new Q(type, code, stem, body, conditions, List.of(), answer, points);
    }

    private static final DemoPassage HABITS = new DemoPassage("The Power of Small Habits",
            "Many people believe that success comes from big decisions. In reality, it is often the small habits we repeat every day that shape our future. Reading ten pages a night or walking for twenty minutes may seem meaningless at first. Over time, however, these actions add up and produce results that surprise us.",
            List.of("why big decisions matter most", "how small daily habits shape the future", "the benefits of walking every day",
                    "ways to read faster at night", "the difficulty of changing habits"), "②",
            List.of("are easy to forget", "add up over time", "cost a lot of money", "need other people", "happen by chance"));
    private static final DemoPassage MEMORY = new DemoPassage("Why We Forget",
            "Our brains do not store every detail we experience. Instead, they keep information that seems useful and let the rest fade away. This is why reviewing what you have learned within a day is so effective. Each time you recall a memory, the connection in your brain becomes stronger and easier to reach.",
            List.of("the limits of computer memory", "why reviewing helps us remember", "how dreams affect memory",
                    "the danger of studying too much", "why details are useless"), "②",
            List.of("store everything", "let unused information fade", "never change", "work only at night", "copy other brains"));
    private static final DemoPassage PLASTIC = new DemoPassage("Plastic in the Ocean",
            "Every year, millions of tons of plastic end up in the ocean. Sea animals often mistake small pieces of plastic for food, which can make them sick or even kill them. Some cities have banned plastic bags to reduce this waste. Experts say that individual choices, such as carrying a reusable bottle, also make a real difference.",
            List.of("how plastic is made", "plastic pollution and ways to reduce it", "popular sea animals",
                    "why cities ban cars", "the history of reusable bottles"), "②",
            List.of("are too small to matter", "also make a real difference", "only help sea animals", "are against the law",
                    "cost more than they save"));
    private static final DemoPassage BOREDOM = new DemoPassage("The Value of Boredom",
            "Most of us reach for our phones the moment we feel bored. Yet scientists suggest that boredom is not something to avoid. When the mind has nothing to focus on, it begins to wander and make new connections. Many creative ideas are born during these quiet moments of doing nothing.",
            List.of("the dangers of using phones", "how boredom can lead to creativity", "ways to focus better",
                    "why scientists feel bored", "the best phone apps"), "②",
            List.of("waste our time", "lead to creative ideas", "make us tired", "should be avoided", "happen only at school"));
    private static final DemoPassage FAILURE = new DemoPassage("Learning from Failure",
            "Thomas Edison is said to have failed thousands of times before inventing a practical light bulb. Instead of giving up, he viewed each failure as a lesson that showed him what did not work. Students who see mistakes in the same way tend to learn faster, because they are not afraid to try again.",
            List.of("the invention of electricity", "seeing failure as a lesson", "why students fear tests", "Edison's childhood",
                    "how light bulbs work"), "②",
            List.of("a reason to quit", "a lesson to learn from", "a sign of bad luck", "something to hide", "the end of a plan"));
    private static final DemoPassage GARDENS = new DemoPassage("Urban Gardens",
            "In crowded cities, rooftops and empty lots are being turned into small gardens. These urban gardens provide fresh vegetables to neighbors who might not otherwise have access to them. They also give residents a place to meet and work together, which helps build a stronger sense of community.",
            List.of("how to grow vegetables indoors", "the benefits of urban gardens", "problems of crowded cities",
                    "why rooftops are dangerous", "the price of fresh vegetables"), "②",
            List.of("make cities more crowded", "build a sense of community", "cost too much", "replace supermarkets", "attract tourists"));
    private static final DemoPassage SLEEP = new DemoPassage("Sleep and Teenagers",
            "Teenagers often stay up late and struggle to wake up for school. This is not simply a matter of laziness. During adolescence, the body releases the sleep hormone later at night, so teens naturally feel sleepy later than adults. Some schools have started classes later in the morning, and their students report better focus.",
            List.of("why teenagers are lazy", "the biological reason teens sleep late", "how adults should sleep",
                    "the history of school hours", "ways to wake up early"), "②",
            List.of("a choice", "a natural change in the body", "a bad habit", "caused by phones", "the same for adults"));

    private static final List<String> ORDER_CONDITIONS = List.of("[보기]의 어구를 모두 한 번씩 사용할 것", "어형을 바꾸지 말 것");
    private static final List<String> SUMMARY_CONDITIONS = List.of("주어진 첫 글자로 시작할 것", "본문에 나온 단어를 형태 변경 없이 쓸 것");
    private static final List<String> GUIDED_CONDITIONS = List.of("주어진 단어를 모두 사용할 것", "필요시 어형을 바꿀 것", "10단어 이상으로 쓸 것");

    /** 다른 학원 기출: 조건 영작·단답형 중심 */
    private static final ExamSpec OTHER_2023_FINAL = new ExamSpec(2023, 2, ExamType.FINAL, List.of(GARDENS, SLEEP), List.of(
            subjective(QuestionType.SUMMARY_BLANK, "P1", "윗글의 내용을 한 문장으로 요약할 때, 빈칸에 알맞은 말을 본문에서 찾아 쓰시오.",
                    "Urban gardens give neighbors fresh (A) v________ and a place to build (B) c________.", SUMMARY_CONDITIONS,
                    "(A) vegetables (B) community", 5.0),
            subjective(QuestionType.GUIDED_WRITING, "P2", "다음 우리말과 같도록 [조건]에 맞게 영작하시오.",
                    "일부 학교는 아침 수업을 더 늦게 시작했고, 학생들은 집중력이 좋아졌다고 말한다. (start, later, report, focus)",
                    GUIDED_CONDITIONS, "Some schools have started classes later in the morning, and their students report better focus.", 6.0),
            subjective(QuestionType.SUBJ_SHORT_ANSWER, "P2", "윗글에서 십대가 늦게 졸린 이유가 되는 것을 찾아 영어로 쓰시오.", null, List.of(),
                    "the sleep hormone", 4.0)), List.of());

    private static final ExamSpec OWN_2024_MIDTERM = new ExamSpec(2024, 1, ExamType.MIDTERM, List.of(HABITS, MEMORY), List.of(
            subjective(QuestionType.SUMMARY_BLANK, "P1", "윗글의 내용을 요약할 때, 빈칸에 알맞은 말을 본문에서 찾아 쓰시오.",
                    "Small (A) h________ that we repeat every day (B) s________ our future.", SUMMARY_CONDITIONS,
                    "(A) habits (B) shape", 5.0),
            subjective(QuestionType.SENTENCE_ORDER, "P2", "윗글의 내용과 일치하도록 [보기]의 어구를 배열하여 문장을 완성하시오.",
                    "[보기] reach / easier / becomes / and / stronger / to / the connection", ORDER_CONDITIONS,
                    "the connection becomes stronger and easier to reach", 5.0),
            subjective(QuestionType.GUIDED_WRITING, "P2", "다음 우리말과 같도록 [조건]에 맞게 영작하시오.",
                    "하루 안에 배운 것을 복습하는 것이 그렇게 효과적인 이유가 이것이다. (why, review, within, effective)",
                    GUIDED_CONDITIONS, "This is why reviewing what you have learned within a day is so effective.", 6.0)), List.of());

    private static final ExamSpec OWN_2024_FINAL = new ExamSpec(2024, 2, ExamType.FINAL, List.of(PLASTIC, BOREDOM), List.of(
            subjective(QuestionType.SUMMARY_BLANK, "P1", "윗글의 내용을 요약할 때, 빈칸에 알맞은 말을 본문에서 찾아 쓰시오.",
                    "Plastic in the ocean harms sea animals, but bans and (A) i________ choices can (B) r________ the waste.",
                    SUMMARY_CONDITIONS, "(A) individual (B) reduce", 5.0),
            subjective(QuestionType.SENTENCE_ORDER, "P2", "윗글의 내용과 일치하도록 [보기]의 어구를 배열하여 문장을 완성하시오.",
                    "[보기] wander / it / to / begins / and make / new connections", ORDER_CONDITIONS,
                    "it begins to wander and make new connections", 5.0),
            subjective(QuestionType.GRAMMAR_FIX, "P1", "윗글의 밑줄 친 ①~⑤ 중 어법상 틀린 것을 찾아 바르게 고쳐 쓰시오.",
                    "Sea animals often mistake small pieces of plastic for food, which can make them ①sick or even ②killing them.",
                    List.of("틀린 것은 1개임", "기호와 고친 표현을 함께 쓸 것"), "② killing → kill", 4.0)),
            List.of("2쪽 하단 서술형 3번 배점이 흐리게 인쇄되어 4점으로 읽었습니다."));

    private static final ExamSpec OWN_2025_MIDTERM = new ExamSpec(2025, 1, ExamType.MIDTERM, List.of(FAILURE, SLEEP), List.of(
            subjective(QuestionType.SUMMARY_BLANK, "P1", "윗글의 내용을 요약할 때, 빈칸에 알맞은 말을 본문에서 찾아 쓰시오.",
                    "Seeing each (A) f________ as a lesson helps students (B) l________ faster.", SUMMARY_CONDITIONS,
                    "(A) failure (B) learn", 5.0),
            subjective(QuestionType.SENTENCE_ORDER, "P1", "윗글의 내용과 일치하도록 [보기]의 어구를 배열하여 문장을 완성하시오.",
                    "[보기] what / showed / did not work / him / a lesson / that", ORDER_CONDITIONS,
                    "a lesson that showed him what did not work", 5.0),
            subjective(QuestionType.SENTENCE_ORDER, "P2", "윗글의 내용과 일치하도록 [보기]의 어구를 배열하여 문장을 완성하시오.",
                    "[보기] releases / later at night / the body / the sleep hormone", ORDER_CONDITIONS,
                    "the body releases the sleep hormone later at night", 5.0),
            subjective(QuestionType.GRAMMAR_FIX, "P2", "윗글의 밑줄 친 ①~⑤ 중 어법상 틀린 것을 찾아 바르게 고쳐 쓰시오.",
                    "Teenagers often stay up late and ①struggle ②waking up for school.",
                    List.of("틀린 것은 1개임", "기호와 고친 표현을 함께 쓸 것"), "② waking → to wake", 4.0)), List.of());

    private static final ExamSpec HYUNDAI_2025_MIDTERM = new ExamSpec(2025, 1, ExamType.MIDTERM, List.of(MEMORY, PLASTIC), List.of(
            subjective(QuestionType.GUIDED_WRITING, "P1", "다음 우리말과 같도록 [조건]에 맞게 영작하시오.",
                    "우리의 뇌는 우리가 경험하는 모든 세부 사항을 저장하지는 않는다. (brains, store, every, experience)",
                    GUIDED_CONDITIONS, "Our brains do not store every detail we experience.", 6.0),
            subjective(QuestionType.SUMMARY_BLANK, "P2", "윗글의 내용을 요약할 때, 빈칸에 알맞은 말을 본문에서 찾아 쓰시오.",
                    "Sea animals (A) ________ plastic for food, so cities have (B) ________ plastic bags.",
                    List.of("본문에 나온 단어를 형태 변경 없이 쓸 것"), "(A) mistake (B) banned", 5.0),
            subjective(QuestionType.SUBJ_SHORT_ANSWER, "P2", "윗글에서 전문가들이 예로 든 개인의 선택을 찾아 영어로 쓰시오.", null, List.of(),
                    "carrying a reusable bottle", 4.0)), List.of());
}
