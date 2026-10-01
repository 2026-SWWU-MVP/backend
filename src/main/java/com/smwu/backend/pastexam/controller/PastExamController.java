package com.smwu.backend.pastexam.controller;

import com.smwu.backend.pastexam.dto.ExtractionResultResponse;
import com.smwu.backend.pastexam.dto.PastExamResponse;
import com.smwu.backend.pastexam.dto.UpdatePastPassageRequest;
import com.smwu.backend.pastexam.dto.UpdatePastQuestionRequest;
import com.smwu.backend.pastexam.dto.UploadPastExamRequest;
import com.smwu.backend.pastexam.service.PastExamExtractionService;
import com.smwu.backend.pastexam.service.PastExamReviewService;
import com.smwu.backend.pastexam.service.PastExamService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 기출 업로드 → 추출(비동기) → 검수·수정.
 * <ol>
 *   <li>POST /api/workspaces/{workspaceId}/past-exams 로 PDF 업로드 (status=UPLOADED)</li>
 *   <li>POST /api/past-exams/{id}/analyze 로 추출 시작 → 202, status=EXTRACTING</li>
 *   <li>GET /api/past-exams/{id} 를 폴링해 status가 EXTRACTED 또는 FAILED가 되면 종료 (보통 1~2분)</li>
 *   <li>GET /api/past-exams/{id}/questions 로 추출 결과와 점검 이슈 조회, PATCH로 수정</li>
 * </ol>
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class PastExamController {

    private final PastExamService pastExamService;
    private final PastExamExtractionService extractionService;
    private final PastExamReviewService reviewService;

    @PostMapping(value = "/workspaces/{workspaceId}/past-exams", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public PastExamResponse upload(@PathVariable Long workspaceId,
                                   @RequestPart("file") MultipartFile file,
                                   @Valid @ModelAttribute UploadPastExamRequest request) {
        return pastExamService.upload(workspaceId, file, request);
    }

    @GetMapping("/workspaces/{workspaceId}/past-exams")
    public List<PastExamResponse> list(@PathVariable Long workspaceId) {
        return pastExamService.list(workspaceId);
    }

    @GetMapping("/past-exams/{examId}")
    public PastExamResponse get(@PathVariable Long examId) {
        return pastExamService.get(examId);
    }

    @DeleteMapping("/past-exams/{examId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long examId) {
        pastExamService.delete(examId);
    }

    /** 추출 시작 (다시 추출하면 기존 결과와 수정 내용은 새 결과로 바뀐다) */
    @PostMapping("/past-exams/{examId}/analyze")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public PastExamResponse analyze(@PathVariable Long examId) {
        return extractionService.requestExtraction(examId);
    }

    @GetMapping("/past-exams/{examId}/questions")
    public ExtractionResultResponse questions(@PathVariable Long examId) {
        return reviewService.getResult(examId);
    }

    /** 문항 수정. 응답은 점검 이슈를 다시 계산한 전체 추출 결과 */
    @PatchMapping("/past-questions/{questionId}")
    public ExtractionResultResponse updateQuestion(@PathVariable Long questionId,
                                                   @Valid @RequestBody UpdatePastQuestionRequest request) {
        return reviewService.updateQuestion(questionId, request);
    }

    /** 지문 수정 (밑줄 대괄호 보정 등). 응답은 점검 이슈를 다시 계산한 전체 추출 결과 */
    @PatchMapping("/past-passages/{passageId}")
    public ExtractionResultResponse updatePassage(@PathVariable Long passageId,
                                                  @Valid @RequestBody UpdatePastPassageRequest request) {
        return reviewService.updatePassage(passageId, request);
    }
}
