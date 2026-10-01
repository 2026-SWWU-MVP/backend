package com.smwu.backend.pastexam.service;

import com.smwu.backend.common.exception.BusinessException;
import com.smwu.backend.common.exception.ErrorCode;
import com.smwu.backend.common.storage.FileStorage;
import com.smwu.backend.document.PdfPageImages;
import com.smwu.backend.document.PdfPageImages.PdfInfo;
import com.smwu.backend.pastexam.domain.PastExam;
import com.smwu.backend.pastexam.domain.PastExamStatus;
import com.smwu.backend.pastexam.dto.PastExamResponse;
import com.smwu.backend.pastexam.dto.UploadPastExamRequest;
import com.smwu.backend.pastexam.extraction.ExtractedExam.QuestionSection;
import com.smwu.backend.pastexam.repository.PastExamRepository;
import com.smwu.backend.pastexam.repository.PastPassageRepository;
import com.smwu.backend.pastexam.repository.PastQuestionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 기출 PDF 업로드·조회·삭제 */
@Service
@RequiredArgsConstructor
public class PastExamService {

    static final int MAX_PAGES = 30;
    private static final byte[] PDF_MAGIC = "%PDF-".getBytes(StandardCharsets.US_ASCII);
    private static final String STORAGE_DIR = "past-exams";

    private final PastExamRepository pastExamRepository;
    private final PastQuestionRepository pastQuestionRepository;
    private final PastPassageRepository pastPassageRepository;
    private final FileStorage fileStorage;

    @Transactional
    public PastExamResponse upload(Long workspaceId, MultipartFile file, UploadPastExamRequest request) {
        checkWorkspaceAccess(workspaceId);
        byte[] content = readPdf(file);
        PdfInfo info;
        try {
            info = PdfPageImages.inspect(content);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.INVALID_FILE, "PDF를 열 수 없습니다. 손상되었거나 암호가 걸린 파일인지 확인해 주세요.");
        }
        if (info.pageCount() == 0 || info.pageCount() > MAX_PAGES) {
            throw new BusinessException(ErrorCode.INVALID_FILE, "기출 PDF는 1~" + MAX_PAGES + "페이지만 올릴 수 있습니다.");
        }

        String filePath = fileStorage.save(STORAGE_DIR, "pdf", content);
        PastExam exam = PastExam.builder()
                .workspaceId(workspaceId)
                .examYear(request.examYear())
                .semester(request.semester())
                .examType(request.examType())
                .originalFilename(originalFilename(file))
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

    /**
     * TODO(#10): WorkspaceAccessChecker로 현재 사용자의 학원 워크스페이스인지 확인 (아니면 404).
     * 플랫폼 트랙의 로그인(#6)·워크스페이스(#9)·데이터 격리(#10)가 들어오면 연결한다.
     */
    private void checkWorkspaceAccess(Long workspaceId) {
    }

    private static byte[] readPdf(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_FILE, "업로드할 PDF 파일이 없습니다.");
        }
        byte[] content;
        try {
            content = file.getBytes();
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.INVALID_FILE, "파일을 읽지 못했습니다.");
        }
        // 확장자·Content-Type은 믿지 않고 파일 시작 부분으로 PDF인지 확인
        if (content.length < PDF_MAGIC.length || !Arrays.equals(content, 0, PDF_MAGIC.length, PDF_MAGIC, 0, PDF_MAGIC.length)) {
            throw new BusinessException(ErrorCode.INVALID_FILE, "PDF 파일만 올릴 수 있습니다. HWP나 사진은 PDF로 변환해 주세요.");
        }
        return content;
    }

    private static String originalFilename(MultipartFile file) {
        String name = file.getOriginalFilename();
        if (name == null || name.isBlank()) {
            return "past-exam.pdf";
        }
        // 브라우저에 따라 경로가 붙어 오는 경우가 있어 파일 이름만 남긴다
        String fileName = name.replace('\\', '/');
        fileName = fileName.substring(fileName.lastIndexOf('/') + 1);
        return fileName.length() > 200 ? fileName.substring(fileName.length() - 200) : fileName;
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
