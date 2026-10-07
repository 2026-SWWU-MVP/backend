package com.smwu.backend.profile.service;

import com.smwu.backend.common.exception.BusinessException;
import com.smwu.backend.common.exception.ErrorCode;
import com.smwu.backend.pastexam.domain.PastExam;
import com.smwu.backend.pastexam.domain.PastQuestion;
import com.smwu.backend.pastexam.extraction.QuestionType;
import com.smwu.backend.profile.domain.ProfileOrigin;
import com.smwu.backend.profile.domain.ProfileRule;
import com.smwu.backend.profile.domain.ProfileRule.RuleCategory;
import com.smwu.backend.profile.domain.ProfileRule.RuleSource;
import com.smwu.backend.profile.domain.ProfileStats;
import com.smwu.backend.profile.domain.ProfileStatus;
import com.smwu.backend.profile.domain.SchoolProfile;
import com.smwu.backend.profile.domain.TeacherNote;
import com.smwu.backend.profile.dto.ProfileResponse;
import com.smwu.backend.profile.repository.SchoolProfileRepository;
import com.smwu.backend.profile.service.ProfileSourceLoader.Source;
import com.smwu.backend.schooldb.domain.SchoolExam;
import com.smwu.backend.schooldb.dto.SchoolTrendSummaryResponse;
import com.smwu.backend.schooldb.service.SchoolDbProfileSource;
import com.smwu.backend.schooldb.service.SchoolDbProfileSource.Input;
import com.smwu.backend.schooldb.service.SchoolTrendCalculator;
import com.smwu.backend.schooldb.service.SchoolTrendSummaryService;
import com.smwu.backend.auth.web.CurrentUserContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 워크스페이스의 추출 완료 기출 + 학교 DB 경향(#41)으로 출제 프로필 새 버전(DRAFT)을 만든다.
 * <ul>
 *   <li>통계: 내 기출 + 학교 DB 회차(다른 학원 기출, 내 기출과 같은 회차는 제외) 코드 집계</li>
 *   <li>지문당 유형 구성: 위 서술형 유형 수를 최근 회차 가중으로 나눈다</li>
 *   <li>출제 규칙·대표 문항: LLM 요약 + 코드 검증 ({@link RuleSummarizer}). 학교 DB에서만 확인되는 규칙은 [학교 DB]</li>
 *   <li>내 기출이 없으면 학교 DB 경향 요약을 [학교 DB] 규칙으로 쓴다 (신규 학원도 첫날부터 프로필)</li>
 *   <li>"다음 시험에도 적용"한 강사 의견과 그 의견에서 나온 강사 규칙은 유지.
 *       이어받는 기준은 현재 확정본(없으면 최신 버전) — 확정 전 초안에서 지운 내용이 섞이지 않게</li>
 * </ul>
 * LLM 호출(수십 초) 동안 트랜잭션을 열지 않고, 저장만 짧은 트랜잭션으로 처리한다.
 */
@Slf4j
@Service
public class ProfileAnalysisService {

    private final ProfileSourceLoader sourceLoader;
    private final SchoolProfileRepository profileRepository;
    private final RuleSummarizer ruleSummarizer;
    private final ProfileQueryService profileQueryService;
    private final SchoolDbProfileSource schoolDbSource;
    private final SchoolTrendSummaryService trendSummaryService;
    private final TransactionTemplate transactionTemplate;

    public ProfileAnalysisService(ProfileSourceLoader sourceLoader, SchoolProfileRepository profileRepository,
                                  RuleSummarizer ruleSummarizer, ProfileQueryService profileQueryService,
                                  SchoolDbProfileSource schoolDbSource, SchoolTrendSummaryService trendSummaryService,
                                  PlatformTransactionManager transactionManager) {
        this.sourceLoader = sourceLoader;
        this.profileRepository = profileRepository;
        this.ruleSummarizer = ruleSummarizer;
        this.profileQueryService = profileQueryService;
        this.schoolDbSource = schoolDbSource;
        this.trendSummaryService = trendSummaryService;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    public ProfileResponse createFromPastExams(Long workspaceId) {
        profileQueryService.checkWorkspaceAccess(workspaceId);

        Source source = sourceLoader.loadWorkspace(workspaceId);
        Optional<Input> schoolDb = schoolDbSource.load(workspaceId, source.exams().stream().map(PastExam::getId).toList());
        if (source.exams().isEmpty() && schoolDb.isEmpty()) {
            throw new BusinessException(ErrorCode.NO_EXTRACTED_PAST_EXAM);
        }
        List<SchoolExam> rounds = schoolDb.map(Input::rounds).orElse(List.of());
        ProfileStats own = source.exams().isEmpty() ? null : ProfileStatsCalculator.calculate(source.exams().size(), source.questions());
        ProfileStats stats = rounds.isEmpty() ? own : SchoolDbProfileSource.merge(own, rounds);
        Map<QuestionType, Integer> typeMix = typeMix(source, rounds);
        RuleSummarizer.Result summary = source.exams().isEmpty()
                ? schoolDbRules(schoolDb.get())
                : ruleSummarizer.summarize(source.target(), stats, source.exams(), source.subjectiveQuestions(),
                source.passagesByExam(), schoolDb.map(Input::description).orElse(null));

        SchoolProfile saved = transactionTemplate.execute(status -> {
            Optional<SchoolProfile> latest = profileRepository.findTopByWorkspaceIdOrderByVersionDesc(workspaceId);
            Optional<SchoolProfile> base = profileRepository.findByWorkspaceIdAndStatus(workspaceId, ProfileStatus.CONFIRMED)
                    .stream().findFirst().or(() -> latest);
            Carried carried = carryPersistent(base, summary.rules());
            SchoolProfile profile = SchoolProfile.builder()
                    .workspaceId(workspaceId)
                    .version(latest.map(p -> p.getVersion() + 1).orElse(1))
                    .parentId(base.map(SchoolProfile::getId).orElse(null))
                    .origin(ProfileOrigin.INITIAL_ANALYSIS)
                    .stats(stats)
                    .rules(carried.rules())
                    .typeMixPerPassage(typeMix)
                    .teacherNotes(carried.notes())
                    .changeSummary(changeSummary(base, source.exams(), carried.notes().size(), schoolDb))
                    .exampleQuestionIds(summary.exampleQuestionIds())
                    .sourceExamIds(source.exams().stream().map(PastExam::getId).toList())
                    .llmModel(summary.model())
                    .createdBy(CurrentUserContext.userIdOrNull())
                    .build();
            return profileRepository.save(profile);
        });
        log.info("출제 프로필 생성 workspaceId={} v{} 기출 {}개 + 학교 DB {}회분, 규칙 {}개", workspaceId, saved.getVersion(),
                source.exams().size(), rounds.size(), saved.getRules().size());
        return profileQueryService.get(saved.getId());
    }

    private record Carried(List<ProfileRule> rules, List<TeacherNote> notes) {
    }

    /**
     * 내 기출과 학교 DB 회차의 서술형 유형 수를 최근 회차 가중(같은 해 ×1.0, 1년 전 ×0.5, 그 이전 ×0.25)으로 더해
     * 지문당 3문항으로 나눈다. 기준 연도는 가장 최근 기출의 연도.
     */
    static Map<QuestionType, Integer> typeMix(Source source, List<SchoolExam> rounds) {
        Map<Long, Integer> yearByExam = new HashMap<>();
        source.exams().forEach(e -> yearByExam.put(e.getId(), e.getExamYear()));
        int latest = Math.max(
                source.exams().stream().mapToInt(PastExam::getExamYear).max().orElse(Integer.MIN_VALUE),
                rounds.stream().mapToInt(SchoolExam::getExamYear).max().orElse(Integer.MIN_VALUE));
        Map<QuestionType, Double> counts = new EnumMap<>(QuestionType.class);
        for (PastQuestion q : source.subjectiveQuestions()) {
            counts.merge(q.getType(), SchoolTrendCalculator.weight(latest - yearByExam.get(q.getPastExamId())), Double::sum);
        }
        for (SchoolExam round : rounds) {
            double w = SchoolTrendCalculator.weight(latest - round.getExamYear());
            round.getStats().typeCounts().forEach((type, count) -> {
                if (!type.name().startsWith("OBJ_")) {
                    counts.merge(type, w * count, Double::sum);
                }
            });
        }
        return ProfileStatsCalculator.apportionMix(counts);
    }

    /** 내 기출이 없을 때: 학교 경향 요약(캐시)의 문장을 [학교 DB] 규칙으로. 대표 문항은 없다 */
    private RuleSummarizer.Result schoolDbRules(Input schoolDb) {
        SchoolTrendSummaryResponse trend = trendSummaryService.summary(schoolDb.schoolId(), schoolDb.grade());
        List<ProfileRule> rules = new ArrayList<>();
        for (String point : trend.points()) {
            rules.add(new ProfileRule("r" + (rules.size() + 1), point, RuleCategory.OTHER, RuleSource.SCHOOL_DB, List.of(), false, null));
        }
        return new RuleSummarizer.Result(rules, List.of(), trend.llmModel());
    }

    /** 새 기출 규칙 뒤에, 다음 시험에도 적용하기로 한 강사 의견과 그 의견의 강사 규칙을 붙인다 (noteIndex 다시 매김) */
    static Carried carryPersistent(Optional<SchoolProfile> latest, List<ProfileRule> pastExamRules) {
        List<ProfileRule> rules = new ArrayList<>(pastExamRules);
        List<TeacherNote> notes = new ArrayList<>();
        if (latest.isEmpty()) {
            return new Carried(rules, notes);
        }
        Map<Integer, Integer> noteIndexMap = new HashMap<>();
        List<TeacherNote> previousNotes = latest.get().getTeacherNotes();
        for (int i = 0; i < previousNotes.size(); i++) {
            if (previousNotes.get(i).persistent()) {
                noteIndexMap.put(i, notes.size());
                notes.add(previousNotes.get(i));
            }
        }
        for (ProfileRule rule : latest.get().getRules()) {
            if (rule.source() == RuleSource.TEACHER && rule.noteIndex() != null && noteIndexMap.containsKey(rule.noteIndex())) {
                rules.add(new ProfileRule("r" + (rules.size() + 1), rule.text(), rule.category(), RuleSource.TEACHER,
                        List.of(), false, noteIndexMap.get(rule.noteIndex())));
            }
        }
        return new Carried(rules, notes);
    }

    private static List<String> changeSummary(Optional<SchoolProfile> latest, List<PastExam> exams, int carriedNotes,
                                              Optional<Input> schoolDb) {
        List<String> summary = new ArrayList<>();
        if (exams.isEmpty()) {
            summary.add("우리 학원 기출 없이 " + schoolDb.orElseThrow().basis() + " 기준으로 출제 프로필을 만들었습니다."
                    + " 기출을 올리면 더 정확해집니다.");
        } else {
            String titles = exams.stream().map(RuleSummarizer::examTitle).collect(Collectors.joining(", "));
            summary.add(latest.isEmpty()
                    ? "기출 " + exams.size() + "개(" + titles + ")로 첫 출제 프로필을 만들었습니다."
                    : "기출 " + exams.size() + "개(" + titles + ")로 다시 분석했습니다. (이전 v" + latest.get().getVersion()
                    + ": 기출 " + latest.get().getSourceExamIds().size() + "개)");
            schoolDb.ifPresent(db -> summary.add(db.basis() + "(다른 학원 기출, 우리 기출과 같은 회차 제외)의 경향을 함께 반영했습니다."));
        }
        if (carriedNotes > 0) {
            summary.add("다음 시험에도 적용하기로 한 강사 의견 " + carriedNotes + "개를 유지했습니다.");
        }
        return summary;
    }
}
