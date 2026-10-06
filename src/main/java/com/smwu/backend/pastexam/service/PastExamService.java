package com.smwu.backend.pastexam.service;

import com.smwu.backend.common.exception.BusinessException;
import com.smwu.backend.common.exception.ErrorCode;
import com.smwu.backend.common.storage.FileStorage;
import com.smwu.backend.document.PdfUpload;
import com.smwu.backend.document.PdfPageImages.PdfInfo;
import com.smwu.backend.pastexam.domain.PastExam;
import com.smwu.backend.pastexam.domain.PastExamStatus;
import com.smwu.backend.pastexam.dto.PastExamResponse;
import com.smwu.backend.pastexam.dto.UploadPastExamRequest;
import com.smwu.backend.pastexam.extraction.ExtractedExam.QuestionSection;
import com.smwu.backend.pastexam.repository.PastExamRepository;
import com.smwu.backend.pastexam.repository.PastPassageRepository;
import com.smwu.backend.pastexam.repository.PastQuestionRepository;
import com.smwu.backend.pastexam.event.PastExamDeletedEvent;
import com.smwu.backend.workspace.service.WorkspaceAccessChecker;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 기출 PDF 업로드·조회·삭제 */
@Service
@RequiredArgsConstructor
public class PastExamService {

    static final int MAX_PAGES = 30;
    private static final String STORAGE_DIR = "past-exams";

    private final PastExamRepository pastExamRepository;
    private final PastQuestionRepository pastQuestionRepository;
    private final PastPassageRepository pastPassageRepository;
    private final FileStorage fileStorage;
    private final WorkspaceAccessChecker accessChecker;
    private final ApplicationEventPublisher eventPublisher;

    @Transactional
    public PastExamResponse upload(Long workspaceId, MultipartFile file, UploadPastExamRequest request) {
        checkWorkspaceAccess(workspaceId);
        PdfUpload.Validated pdf = PdfUpload.validate(file, MAX_PAGES, "기출 PDF", "past-exam.pdf");
        byte[] content = pdf.content();
        PdfInfo info = pdf.info();

        String filePath = fileStorage.save(STORAGE_DIR, "pdf", content);
        PastExam exam = PastExam.builder()
                .workspaceId(workspaceId)
                .examYear(request.examYear())
                .semester(request.semester())
                .examType(request.examType())
                .originalFilename(pdf.filename())
                .filePath(filePath)
                .fileSize(content.length)
                .pageCount(info.pageCount())
                .textLayer(info.textLayer())
                .build();
        pastExamRepository.save(exam);
        deleteFileOnRollback(filePath);
        return PastExamResponse.of(exam, 0, 0);
    }

    @Transactional(readOnly = true)
    public List<PastExamResponse> list(Long workspaceId) {
        checkWorkspaceAccess(workspaceId);
        List<PastExam> exams = pastExamRepository.findByWorkspaceIdOrderByExamYearDescSemesterDescIdDesc(workspaceId);
        Map<Long, long[]> counts = new HashMap<>();
        if (!exams.isEmpty()) {
            for (Object[] row : pastQuestionRepository.countBySection(exams.stream().map(PastExam::getId).toList())) {
                long[] c = counts.computeIfAbsent((Long) row[0], id -> new long[2]);
                c[row[1] == QuestionSection.OBJECTIVE ? 0 : 1] = (Long) row[2];
            }
        }
        return exams.stream()
                .map(e -> {
                    long[] c = counts.getOrDefault(e.getId(), new long[2]);
                    return PastExamResponse.of(e, c[0], c[1]);
                })
                .toList();
    }

    @Transactional(readOnly = true)
    public PastExamResponse get(Long examId) {
        PastExam exam = getExam(examId);
        return PastExamResponse.of(exam,
                pastQuestionRepository.countByPastExamIdAndSection(examId, QuestionSection.OBJECTIVE),
                pastQuestionRepository.countByPastExamIdAndSection(examId, QuestionSection.SUBJECTIVE));
    }

    @Transactional
    public void delete(Long examId) {
        PastExam exam = getExam(examId);
        if (exam.getStatus() == PastExamStatus.EXTRACTING) {
            throw new BusinessException(ErrorCode.EXTRACTION_IN_PROGRESS);
        }
        pastQuestionRepository.deleteByPastExamId(examId);
        pastPassageRepository.deleteByPastExamId(examId);
        pastExamRepository.delete(exam);
        eventPublisher.publishEvent(new PastExamDeletedEvent(examId));
        String filePath = exam.getFilePath();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                fileStorage.delete(filePath);
            }
        });
    }

    /** 기출 시험지를 찾고 접근 권한을 확인한다. 다른 서비스도 이 메서드로 시험지를 가져온다 */
    public PastExam getExam(Long examId) {
        PastExam exam = pastExamRepository.findById(examId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        checkWorkspaceAccess(exam.getWorkspaceId());
        return exam;
    }

    /** 현재 사용자의 학원 워크스페이스인지 확인 (아니면 404) */
    private void checkWorkspaceAccess(Long workspaceId) {
        accessChecker.check(workspaceId);
    }

    private void deleteFileOnRollback(String filePath) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status == STATUS_ROLLED_BACK) {
                    fileStorage.delete(filePath);
                }
            }
        });
    }
}
