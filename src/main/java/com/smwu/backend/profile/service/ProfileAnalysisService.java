package com.smwu.backend.profile.service;

import com.smwu.backend.common.exception.BusinessException;
import com.smwu.backend.common.exception.ErrorCode;
import com.smwu.backend.pastexam.domain.PastExam;
import com.smwu.backend.pastexam.domain.PastExamStatus;
import com.smwu.backend.pastexam.domain.PastPassage;
import com.smwu.backend.pastexam.domain.PastQuestion;
import com.smwu.backend.pastexam.extraction.ExtractedExam.QuestionSection;
import com.smwu.backend.pastexam.repository.PastExamRepository;
import com.smwu.backend.pastexam.repository.PastPassageRepository;
import com.smwu.backend.pastexam.repository.PastQuestionRepository;
import com.smwu.backend.pastexam.extraction.QuestionType;
import com.smwu.backend.profile.domain.ProfileOrigin;
import com.smwu.backend.profile.domain.ProfileStats;
import com.smwu.backend.profile.domain.SchoolProfile;
import com.smwu.backend.profile.domain.TeacherNote;
import com.smwu.backend.profile.dto.ProfileResponse;
import com.smwu.backend.profile.repository.SchoolProfileRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Comparator;
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
 * </ul>
 * LLM 호출(수십 초) 동안 트랜잭션을 열지 않고, 저장만 짧은 트랜잭션으로 처리한다.
 */
@Slf4j
@Service
public class ProfileAnalysisService {

    private static final Comparator<PastExam> EXAM_ORDER = Comparator.comparing(PastExam::getExamYear)
            .thenComparing(PastExam::getSemester)
            .thenComparing(PastExam::getExamType)
            .thenComparing(PastExam::getId);

    private final PastExamRepository pastExamRepository;
    private final PastQuestionRepository pastQuestionRepository;
    private final PastPassageRepository pastPassageRepository;
    private final SchoolProfileRepository profileRepository;
    private final RuleSummarizer ruleSummarizer;
    private final ProfileQueryService profileQueryService;
    private final TransactionTemplate transactionTemplate;

    public ProfileAnalysisService(PastExamRepository pastExamRepository, PastQuestionRepository pastQuestionRepository,
                                  PastPassageRepository pastPassageRepository, SchoolProfileRepository profileRepository,
                                  RuleSummarizer ruleSummarizer, ProfileQueryService profileQueryService,
                                  PlatformTransactionManager transactionManager) {
        this.pastExamRepository = pastExamRepository;
        this.pastQuestionRepository = pastQuestionRepository;
        this.pastPassageRepository = pastPassageRepository;
        this.profileRepository = profileRepository;
        this.ruleSummarizer = ruleSummarizer;
        this.profileQueryService = profileQueryService;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    public ProfileResponse createFromPastExams(Long workspaceId) {
        profileQueryService.checkWorkspaceAccess(workspaceId);

        List<PastExam> exams = pastExamRepository.findByWorkspaceIdOrderByExamYearDescSemesterDescIdDesc(workspaceId).stream()
                .filter(e -> e.getStatus() == PastExamStatus.EXTRACTED)
                .sorted(EXAM_ORDER)
                .toList();
        if (exams.isEmpty()) {
            throw new BusinessException(ErrorCode.NO_EXTRACTED_PAST_EXAM);
        }
        List<Long> examIds = exams.stream().map(PastExam::getId).toList();
        Map<Long, Integer> examOrder = new HashMap<>();
        for (int i = 0; i < examIds.size(); i++) {
            examOrder.put(examIds.get(i), i);
        }

        List<PastQuestion> questions = pastQuestionRepository.findByPastExamIdIn(examIds).stream()
                .sorted(Comparator.<PastQuestion>comparingInt(q -> examOrder.get(q.getPastExamId()))
                        .thenComparingInt(PastQuestion::getOrderNo))
                .toList();
        Map<Long, List<PastPassage>> passagesByExam = pastPassageRepository.findByPastExamIdIn(examIds).stream()
                .sorted(Comparator.comparingInt(PastPassage::getOrderNo))
                .collect(Collectors.groupingBy(PastPassage::getPastExamId));

        ProfileStats stats = ProfileStatsCalculator.calculate(exams.size(), questions);
        Map<QuestionType, Integer> typeMix = ProfileStatsCalculator.typeMixPerPassage(questions);
        List<PastQuestion> subjective = questions.stream()
                .filter(q -> q.getSection() == QuestionSection.SUBJECTIVE)
                .toList();
        String target = "같은 학교·학년의 기출 시험지 " + exams.size() + "개 ("
                + exams.stream().map(RuleSummarizer::examTitle).collect(Collectors.joining(", ")) + ")";
        RuleSummarizer.Result summary = ruleSummarizer.summarize(target, stats, exams, subjective, passagesByExam);

        SchoolProfile saved = transactionTemplate.execute(status -> {
            Optional<SchoolProfile> latest = profileRepository.findTopByWorkspaceIdOrderByVersionDesc(workspaceId);
            SchoolProfile profile = SchoolProfile.builder()
                    .workspaceId(workspaceId)
                    .version(latest.map(p -> p.getVersion() + 1).orElse(1))
                    .parentId(latest.map(SchoolProfile::getId).orElse(null))
                    .origin(ProfileOrigin.INITIAL_ANALYSIS)
                    .stats(stats)
                    .rules(summary.rules())
                    .typeMixPerPassage(typeMix)
                    .teacherNotes(persistentNotes(latest))
                    .changeSummary(changeSummary(latest, exams))
                    .exampleQuestionIds(summary.exampleQuestionIds())
                    .sourceExamIds(examIds)
                    .llmModel(summary.model())
                    .build();
            return profileRepository.save(profile);
        });
        log.info("출제 프로필 생성 workspaceId={} v{} 기출 {}개, 규칙 {}개", workspaceId, saved.getVersion(),
                exams.size(), saved.getRules().size());
        return profileQueryService.get(saved.getId());
    }

    /** 다음 시험에도 적용하기로 한 강사 의견은 다시 분석해도 유지한다 (#13에서 입력) */
    private static List<TeacherNote> persistentNotes(Optional<SchoolProfile> latest) {
        return latest.map(p -> p.getTeacherNotes().stream().filter(TeacherNote::persistent).toList()).orElse(List.of());
    }

    private static List<String> changeSummary(Optional<SchoolProfile> latest, List<PastExam> exams) {
        String titles = exams.stream().map(RuleSummarizer::examTitle).collect(Collectors.joining(", "));
        if (latest.isEmpty()) {
            return List.of("기출 " + exams.size() + "개(" + titles + ")로 첫 출제 프로필을 만들었습니다.");
        }
        SchoolProfile previous = latest.get();
        return List.of("기출 " + exams.size() + "개(" + titles + ")로 다시 분석했습니다. (이전 v" + previous.getVersion()
                + ": 기출 " + previous.getSourceExamIds().size() + "개)");
    }
}
