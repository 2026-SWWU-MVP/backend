package com.smwu.backend.document;

import com.smwu.backend.common.exception.BusinessException;
import com.smwu.backend.common.exception.ErrorCode;
import com.smwu.backend.document.PdfPageImages.PdfInfo;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** 업로드된 PDF 공통 검증 (기출, 시험범위 자료) */
public final class PdfUpload {

    private static final byte[] PDF_MAGIC = "%PDF-".getBytes(StandardCharsets.US_ASCII);

    private PdfUpload() {
    }

    /**
     * @param content  PDF 바이트
     * @param info     페이지 수, 텍스트 레이어 여부
     * @param filename 경로를 뗀 원래 파일 이름 (최대 200자)
     */
    public record Validated(byte[] content, PdfInfo info, String filename) {
    }

    /**
     * 파일 시작 부분으로 PDF인지 확인하고(확장자·Content-Type은 믿지 않음), 열리는지와 페이지 수를 확인한다.
     *
     * @param label 오류 메시지에 쓸 이름 (예: "기출 PDF")
     * @throws BusinessException INVALID_FILE
     */
    public static Validated validate(MultipartFile file, int maxPages, String label, String defaultFilename) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_FILE, "업로드할 PDF 파일이 없습니다.");
        }
        byte[] content;
        try {
            content = file.getBytes();
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.INVALID_FILE, "파일을 읽지 못했습니다.");
        }
        if (content.length < PDF_MAGIC.length || !Arrays.equals(content, 0, PDF_MAGIC.length, PDF_MAGIC, 0, PDF_MAGIC.length)) {
            throw new BusinessException(ErrorCode.INVALID_FILE, "PDF 파일만 올릴 수 있습니다. HWP나 사진은 PDF로 변환해 주세요.");
        }
        PdfInfo info;
        try {
            info = PdfPageImages.inspect(content);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.INVALID_FILE, "PDF를 열 수 없습니다. 손상되었거나 암호가 걸린 파일인지 확인해 주세요.");
        }
        if (info.pageCount() == 0 || info.pageCount() > maxPages) {
            throw new BusinessException(ErrorCode.INVALID_FILE, label + "는 1~" + maxPages + "페이지만 올릴 수 있습니다.");
        }
        return new Validated(content, info, filename(file, defaultFilename));
    }

    /** 브라우저에 따라 경로가 붙어 오는 경우가 있어 파일 이름만 남긴다 */
    private static String filename(MultipartFile file, String defaultFilename) {
        String name = file.getOriginalFilename();
        if (name == null || name.isBlank()) {
            return defaultFilename;
        }
        String fileName = name.replace('\\', '/');
        fileName = fileName.substring(fileName.lastIndexOf('/') + 1);
        return fileName.length() > 200 ? fileName.substring(fileName.length() - 200) : fileName;
    }
}
