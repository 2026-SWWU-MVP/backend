package com.smwu.backend.ai;

import java.util.Objects;

/**
 * LLM에 함께 보내는 파일.
 * PDF는 제공사가 텍스트(+일부 이미지)로 변환해서 읽고, 이미지(image/*)는 모델이 그대로 본다.
 */
public record LlmFile(String filename, String mimeType, byte[] content) {

    public LlmFile {
        Objects.requireNonNull(filename, "filename");
        Objects.requireNonNull(mimeType, "mimeType");
        Objects.requireNonNull(content, "content");
    }

    public static LlmFile pdf(String filename, byte[] content) {
        return new LlmFile(filename, "application/pdf", content);
    }

    public static LlmFile jpeg(String filename, byte[] content) {
        return new LlmFile(filename, "image/jpeg", content);
    }

    public boolean isImage() {
        return mimeType.startsWith("image/");
    }
}
