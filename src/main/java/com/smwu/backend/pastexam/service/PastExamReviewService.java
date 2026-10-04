package com.smwu.backend.pastexam.service;

import com.smwu.backend.common.exception.BusinessException;
import com.smwu.backend.common.exception.ErrorCode;
import com.smwu.backend.pastexam.domain.PastExam;
import com.smwu.backend.pastexam.domain.PastPassage;
import com.smwu.backend.pastexam.domain.PastQuestion;
import com.smwu.backend.pastexam.dto.ExtractionResultResponse;
import com.smwu.backend.pastexam.dto.ExtractionResultResponse.PassageView;
import com.smwu.backend.pastexam.dto.ExtractionResultResponse.QuestionView;
import com.smwu.backend.pastexam.dto.UpdatePastPassageRequest;
import com.smwu.backend.pastexam.dto.UpdatePastQuestionRequest;
import com.smwu.backend.pastexam.extraction.ExtractedExam;
import com.smwu.backend.pastexam.extraction.ExtractionChecker;
import com.smwu.backend.pastexam.extraction.ExtractionChecker.Issue;
import com.smwu.backend.pastexam.repository.PastPassageRepository;
import com.smwu.backend.pastexam.repository.PastQuestionRepository;
import com.smwu.backend.pastexam.event.PastExamChangedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 추출 결과 검수: 조회와 문항·지문 수정.
 * 점검 이슈는 저장하지 않고 조회할 때마다 현재 내용으로 다시 계산한다 (수정하면 이슈가 바로 사라지거나 생긴다).
 */
@Service
@RequiredArgsConstructor
public class PastExamReviewService {

    private final PastExamService pastExamService;
    private final PastPassageRepository pastPassageRepository;
    private final PastQuestionRepository pastQuestionRepository;
    private final ApplicationEventPublisher eventPublisher;

    @Transactional(readOnly = true)
    public ExtractionResultResponse getResult(Long examId) {
        PastExam exam = pastExamService.getExam(examId);
        return buildResult(exam);
    }

    @Transactional
    public ExtractionResultResponse updateQuestion(Long questionId, UpdatePastQuestionRequest request) {
        PastQuestion question = pastQuestionRepository.findById(questionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        PastExam exam = pastExamService.getExam(question.getPastExamId());
        exam.requireExtracted();

        if (request.passageCodes() != null) {
            Set<String> codes = new HashSet<>();
            pastPassageRepository.findByPastExamIdOrderByOrderNo(exam.getId()).forEach(p -> codes.add(p.getCode()));
            List<String> unknown = request.passageCodes().stream().filter(c -> !codes.contains(c)).toList();
            if (!unknown.isEmpty()) {
                throw new BusinessException(ErrorCode.INVALID_REQUEST, "없는 지문을 참조합니다: " + unknown);
            }
        }
        question.edit(request.section(), request.no(), request.type(), request.passageCodes(), request.stem(),
                request.body(), request.conditions(), request.choices(), request.answer(), request.points(),
                Boolean.TRUE.equals(request.clearPoints()));
        pastQuestionRepository.flush();
        eventPublisher.publishEvent(new PastExamChangedEvent(exam.getId()));
        return buildResult(exam);
    }

    @Transactional
    public ExtractionResultResponse updatePassage(Long passageId, UpdatePastPassageRequest request) {
        PastPassage passage = pastPassageRepository.findById(passageId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        PastExam exam = pastExamService.getExam(passage.getPastExamId());
        exam.requireExtracted();

        passage.edit(request.title(), request.text());
        pastPassageRepository.flush();
        return buildResult(exam);
    }

    private ExtractionResultResponse buildResult(PastExam exam) {
        List<PastPassage> passages = pastPassageRepository.findByPastExamIdOrderByOrderNo(exam.getId());
        List<PastQuestion> questions = pastQuestionRepository.findByPastExamIdOrderByOrderNo(exam.getId());
        List<Issue> issues = ExtractionChecker.check(ExtractedExam.from(passages, questions, exam.getWarnings()));

        List<PassageView> passageViews = passages.stream()
                .map(p -> PassageView.of(p, issues.stream()
                        .filter(i -> p.getCode().equals(i.passageId()))
                        .map(Issue::message)
                        .toList()))
                .toList();
        List<QuestionView> questionViews = questions.stream()
                .map(q -> QuestionView.of(q, issues.stream()
                        .filter(i -> i.isFor(q.getSection(), q.getNo()))
                        .map(Issue::message)
                        .toList()))
                .toList();
        List<String> examIssues = issues.stream()
                .filter(i -> i.section() == null && i.passageId() == null)
                .map(Issue::message)
                .toList();

        return new ExtractionResultResponse(exam.getId(), exam.getStatus(), passageViews, questionViews, examIssues,
                List.copyOf(exam.getWarnings()), issues.size());
    }
}
