package com.smwu.backend.ai;

import java.util.Objects;

/** LLM에 함께 보내는 파일. 멀티모달 모델이 PDF를 이미지와 텍스트로 함께 읽는다 */
public record LlmFile(String filename, String mimeType, byte[] content) {

    public LlmFile {
        Objects.requireNonNull(filename, "filename");
        Objects.requireNonNull(mimeType, "mimeType");
        Objects.requireNonNull(content, "content");
    }

    public static LlmFile pdf(String filename, byte[] content) {
        return new LlmFile(filename, "application/pdf", content);
    }
}
