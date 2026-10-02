package com.smwu.backend.profile.service;

import com.smwu.backend.common.exception.BusinessException;
import com.smwu.backend.common.exception.ErrorCode;
import com.smwu.backend.pastexam.domain.PastExam;
import com.smwu.backend.pastexam.extraction.QuestionType;
import com.smwu.backend.profile.domain.ProfileOrigin;
import com.smwu.backend.profile.domain.ProfileRule;
import com.smwu.backend.profile.domain.ProfileRule.RuleSource;
import com.smwu.backend.profile.domain.ProfileStats;
import com.smwu.backend.profile.domain.ProfileStatus;
import com.smwu.backend.profile.domain.SchoolProfile;
import com.smwu.backend.profile.domain.TeacherNote;
import com.smwu.backend.profile.dto.ProfileResponse;
import com.smwu.backend.profile.repository.SchoolProfileRepository;
import com.smwu.backend.profile.service.ProfileSourceLoader.Source;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 워크스페이스의 추출 완료 기출로 출제 프로필 새 버전(DRAFT)을 만든다.
 * <ul>
 *   <li>통계·지문당 유형 구성: 코드 집계 ({@link ProfileStatsCalculator})</li>
 *   <li>출제 규칙·대표 문항: LLM 요약 + 코드 검증 ({@link RuleSummarizer})</li>
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
    private final TransactionTemplate transactionTemplate;

    public ProfileAnalysisService(ProfileSourceLoader sourceLoader, SchoolProfileRepository profileRepository,
                                  RuleSummarizer ruleSummarizer, ProfileQueryService profileQueryService,
                                  PlatformTransactionManager transactionManager) {
        this.sourceLoader = sourceLoader;
        this.profileRepository = profileRepository;
        this.ruleSummarizer = ruleSummarizer;
        this.profileQueryService = profileQueryService;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    public ProfileResponse createFromPastExams(Long workspaceId) {
        profileQueryService.checkWorkspaceAccess(workspaceId);

        Source source = sourceLoader.loadWorkspace(workspaceId);
        if (source.exams().isEmpty()) {
            throw new BusinessException(ErrorCode.NO_EXTRACTED_PAST_EXAM);
        }
        ProfileStats stats = ProfileStatsCalculator.calculate(source.exams().size(), source.questions());
        Map<QuestionType, Integer> typeMix = ProfileStatsCalculator.typeMixPerPassage(source.questions());
        RuleSummarizer.Result summary = ruleSummarizer.summarize(source.target(), stats, source.exams(),
                source.subjectiveQuestions(), source.passagesByExam());

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
                    .changeSummary(changeSummary(base, source.exams(), carried.notes().size()))
                    .exampleQuestionIds(summary.exampleQuestionIds())
                    .sourceExamIds(source.exams().stream().map(PastExam::getId).toList())
                    .llmModel(summary.model())
                    .build();
            return profileRepository.save(profile);
        });
        log.info("출제 프로필 생성 workspaceId={} v{} 기출 {}개, 규칙 {}개", workspaceId, saved.getVersion(),
                source.exams().size(), saved.getRules().size());
        return profileQueryService.get(saved.getId());
    }

    private record Carried(List<ProfileRule> rules, List<TeacherNote> notes) {
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

    private static List<String> changeSummary(Optional<SchoolProfile> latest, List<PastExam> exams, int carriedNotes) {
        String titles = exams.stream().map(RuleSummarizer::examTitle).collect(Collectors.joining(", "));
        if (latest.isEmpty()) {
            return List.of("기출 " + exams.size() + "개(" + titles + ")로 첫 출제 프로필을 만들었습니다.");
        }
        SchoolProfile previous = latest.get();
        List<String> summary = new ArrayList<>();
        summary.add("기출 " + exams.size() + "개(" + titles + ")로 다시 분석했습니다. (이전 v" + previous.getVersion()
                + ": 기출 " + previous.getSourceExamIds().size() + "개)");
        if (carriedNotes > 0) {
            summary.add("다음 시험에도 적용하기로 한 강사 의견 " + carriedNotes + "개를 유지했습니다.");
        }
        return summary;
    }
}
