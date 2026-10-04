package com.smwu.backend.schooldb.service;

import com.smwu.backend.pastexam.domain.PastExam;
import com.smwu.backend.pastexam.domain.PastExamStatus;
import com.smwu.backend.pastexam.domain.PastPassage;
import com.smwu.backend.pastexam.domain.PastQuestion;
import com.smwu.backend.pastexam.event.PastExamChangedEvent;
import com.smwu.backend.pastexam.event.PastExamDeletedEvent;
import com.smwu.backend.pastexam.extraction.ExtractedExam;
import com.smwu.backend.pastexam.extraction.ExtractionChecker;
import com.smwu.backend.pastexam.repository.PastExamRepository;
import com.smwu.backend.pastexam.repository.PastPassageRepository;
import com.smwu.backend.pastexam.repository.PastQuestionRepository;
import com.smwu.backend.schooldb.domain.ExamContribution;
import com.smwu.backend.schooldb.domain.ExamRoundStats;
import com.smwu.backend.schooldb.domain.SchoolExam;
import com.smwu.backend.schooldb.repository.ExamContributionRepository;
import com.smwu.backend.schooldb.repository.SchoolExamRepository;
import com.smwu.backend.workspace.domain.Workspace;
import com.smwu.backend.workspace.repository.WorkspaceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * 학원 기출 → 학교 DB 기여 (설계서 3.10).
 * 기출 추출 결과가 저장되거나 수정·삭제될 때 같은 트랜잭션 안에서 실행된다. LLM 호출이 없어 빠르다.
 * 워크스페이스(학교 + 학년)가 없거나 연도·학기·시험 종류가 비어 있는 기출은 기여하지 않는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SchoolExamContributionService {

    private final SchoolExamRepository schoolExamRepository;
    private final ExamContributionRepository contributionRepository;
    private final WorkspaceRepository workspaceRepository;
    private final PastExamRepository pastExamRepository;
    private final PastPassageRepository pastPassageRepository;
    private final PastQuestionRepository pastQuestionRepository;

    @EventListener
    @Transactional
    public void onChanged(PastExamChangedEvent event) {
        PastExam exam = pastExamRepository.findById(event.pastExamId()).orElse(null);
        if (exam == null || exam.getStatus() != PastExamStatus.EXTRACTED) {
            return;
        }
        Optional<Workspace> workspace = workspaceRepository.findById(exam.getWorkspaceId());
        if (workspace.isEmpty() || exam.getExamYear() == null || exam.getSemester() == null || exam.getExamType() == null) {
            log.debug("학교 DB 기여 건너뜀 examId={} (워크스페이스 또는 회차 정보 없음)", exam.getId());
            return;
        }

        List<PastPassage> passages = pastPassageRepository.findByPastExamIdOrderByOrderNo(exam.getId());
        List<PastQuestion> questions = pastQuestionRepository.findByPastExamIdOrderByOrderNo(exam.getId());
        int issueCount = ExtractionChecker.check(ExtractedExam.from(passages, questions, exam.getWarnings())).size();
        ExamRoundStats stats = ExamRoundStats.of(questions, passages.size());

        Workspace w = workspace.get();
        SchoolExam round = schoolExamRepository.findBySchoolIdAndGradeAndExamYearAndSemesterAndExamType(
                        w.getSchoolId(), w.getGrade(), exam.getExamYear(), exam.getSemester(), exam.getExamType())
                .orElseGet(() -> schoolExamRepository.save(
                        new SchoolExam(w.getSchoolId(), w.getGrade(), exam.getExamYear(), exam.getSemester(), exam.getExamType())));

        ExamContribution contribution = contributionRepository.findByPastExamId(exam.getId())
                .orElseGet(() -> new ExamContribution(round.getId(), exam.getId(), w.getAcademyId()));
        contribution.update(stats, issueCount);
        contributionRepository.save(contribution);
        refresh(round);
        log.info("학교 DB 기여 examId={} → 회차 {} (학교 {}, {}학년 {}년 {}학기 {}), 기여 학원 {}곳", exam.getId(), round.getId(),
                w.getSchoolId(), w.getGrade(), exam.getExamYear(), exam.getSemester(), exam.getExamType(), round.getContributorCount());
    }

    @EventListener
    @Transactional
    public void onDeleted(PastExamDeletedEvent event) {
        contributionRepository.findByPastExamId(event.pastExamId()).ifPresent(contribution -> {
            contributionRepository.delete(contribution);
            contributionRepository.flush();
            schoolExamRepository.findById(contribution.getSchoolExamId()).ifPresent(this::refresh);
        });
    }

    /** 대표 기여와 기여 학원 수를 다시 정한다. 기여가 없으면 회차를 지운다 */
    private void refresh(SchoolExam round) {
        List<ExamContribution> contributions = contributionRepository.findBySchoolExamId(round.getId());
        if (contributions.isEmpty()) {
            schoolExamRepository.delete(round);
            return;
        }
        ExamContribution representative = contributions.stream().min(ExamContribution.REPRESENTATIVE_ORDER).orElseThrow();
        int academies = (int) contributions.stream().map(ExamContribution::getAcademyId).distinct().count();
        round.refresh(representative, academies);
    }
}
