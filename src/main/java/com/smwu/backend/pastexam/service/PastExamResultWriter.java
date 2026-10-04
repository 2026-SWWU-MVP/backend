package com.smwu.backend.pastexam.service;

import com.smwu.backend.ai.LlmResult;
import com.smwu.backend.pastexam.domain.PastExam;
import com.smwu.backend.pastexam.domain.PastPassage;
import com.smwu.backend.pastexam.domain.PastQuestion;
import com.smwu.backend.pastexam.extraction.ExtractedExam;
import com.smwu.backend.pastexam.repository.PastExamRepository;
import com.smwu.backend.pastexam.repository.PastPassageRepository;
import com.smwu.backend.pastexam.repository.PastQuestionRepository;
import com.smwu.backend.pastexam.event.PastExamChangedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/** 추출 결과 저장. 다시 추출하면 기존 지문·문항(사람이 고친 것 포함)은 새 결과로 바뀐다 */
@Component
@RequiredArgsConstructor
public class PastExamResultWriter {

    private final PastExamRepository pastExamRepository;
    private final PastPassageRepository pastPassageRepository;
    private final PastQuestionRepository pastQuestionRepository;
    private final ApplicationEventPublisher eventPublisher;

    @Transactional
    public void saveResult(Long examId, LlmResult<ExtractedExam> result, long elapsedMillis) {
        PastExam exam = pastExamRepository.findById(examId).orElse(null);
        if (exam == null) {
            return;
        }
        pastQuestionRepository.deleteByPastExamId(examId);
        pastPassageRepository.deleteByPastExamId(examId);

        ExtractedExam extracted = result.value();
        List<PastPassage> passages = new ArrayList<>();
        for (int i = 0; i < extracted.passages().size(); i++) {
            ExtractedExam.Passage p = extracted.passages().get(i);
            passages.add(new PastPassage(examId, p.id(), i + 1, p.title(), p.text()));
        }
        List<PastQuestion> questions = new ArrayList<>();
        for (int i = 0; i < extracted.questions().size(); i++) {
            ExtractedExam.Question q = extracted.questions().get(i);
            questions.add(PastQuestion.builder()
                    .pastExamId(examId)
                    .orderNo(i + 1)
                    .section(q.section())
                    .no(q.no())
                    .type(q.type())
                    .passageCodes(q.passageIds())
                    .stem(q.stem())
                    .body(q.body())
                    .conditions(q.conditions())
                    .choices(q.choices())
                    .answer(q.answer())
                    .points(q.points())
                    .build());
        }
        pastPassageRepository.saveAll(passages);
        pastQuestionRepository.saveAll(questions);

        exam.completeExtraction(extracted.warnings(), result.model(), result.inputTokens(), result.outputTokens(),
                elapsedMillis, result.rawText());
        eventPublisher.publishEvent(new PastExamChangedEvent(examId));
    }

    @Transactional
    public void markFailed(Long examId, String reason) {
        pastExamRepository.findById(examId).ifPresent(exam -> exam.failExtraction(reason));
    }
}
