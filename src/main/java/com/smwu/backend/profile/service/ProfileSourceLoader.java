package com.smwu.backend.profile.service;

import com.smwu.backend.pastexam.domain.PastExam;
import com.smwu.backend.pastexam.domain.PastExamStatus;
import com.smwu.backend.pastexam.domain.PastPassage;
import com.smwu.backend.pastexam.domain.PastQuestion;
import com.smwu.backend.pastexam.extraction.ExtractedExam.QuestionSection;
import com.smwu.backend.pastexam.repository.PastExamRepository;
import com.smwu.backend.pastexam.repository.PastPassageRepository;
import com.smwu.backend.pastexam.repository.PastQuestionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** 프로필 분석·재검토에 쓰는 기출(추출 완료분)과 문항·지문을 시험지 순서대로 불러온다 */
@Component
@RequiredArgsConstructor
public class ProfileSourceLoader {

    static final Comparator<PastExam> EXAM_ORDER = Comparator.comparing(PastExam::getExamYear)
            .thenComparing(PastExam::getSemester)
            .thenComparing(PastExam::getExamType)
            .thenComparing(PastExam::getId);

    private final PastExamRepository pastExamRepository;
    private final PastQuestionRepository pastQuestionRepository;
    private final PastPassageRepository pastPassageRepository;

    /**
     * @param exams          추출 완료 기출 (오래된 시험 → 최근 시험)
     * @param questions      모든 문항 (시험지 순서 → 문항 순서)
     * @param passagesByExam 시험지별 지문
     */
    public record Source(List<PastExam> exams, List<PastQuestion> questions, Map<Long, List<PastPassage>> passagesByExam) {

        public List<PastQuestion> subjectiveQuestions() {
            return questions.stream().filter(q -> q.getSection() == QuestionSection.SUBJECTIVE).toList();
        }

        public String target() {
            return "같은 학교·학년의 기출 시험지 " + exams.size() + "개 ("
                    + exams.stream().map(RuleSummarizer::examTitle).collect(Collectors.joining(", ")) + ")";
        }
    }

    /** 워크스페이스의 추출 완료 기출 전부 */
    public Source loadWorkspace(Long workspaceId) {
        return load(pastExamRepository.findByWorkspaceIdOrderByExamYearDescSemesterDescIdDesc(workspaceId));
    }

    /** 지정한 기출 중 아직 남아 있고 추출 완료된 것 (프로필 재검토용) */
    public Source loadExams(Collection<Long> examIds) {
        return load(pastExamRepository.findAllById(examIds));
    }

    private Source load(List<PastExam> candidates) {
        List<PastExam> exams = candidates.stream()
                .filter(e -> e.getStatus() == PastExamStatus.EXTRACTED)
                .sorted(EXAM_ORDER)
                .toList();
        if (exams.isEmpty()) {
            return new Source(List.of(), List.of(), Map.of());
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
        return new Source(exams, questions, passagesByExam);
    }
}
