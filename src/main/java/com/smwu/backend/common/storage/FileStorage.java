package com.smwu.backend.common.storage;

import com.smwu.backend.common.config.AppProperties;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/**
 * 업로드 파일을 로컬 디스크(app.storage.root)에 저장한다. DB에는 루트 기준 상대 경로만 저장한다.
 * 예) save("past-exams", "pdf", bytes) → "past-exams/3f2a....pdf"
 */
@Component
public class FileStorage {

    private final Path root;

    public FileStorage(AppProperties appProperties) {
        this.root = Path.of(appProperties.storage().root()).toAbsolutePath().normalize();
    }

    public String save(String directory, String extension, byte[] content) {
        String relativePath = directory + "/" + UUID.randomUUID() + "." + extension;
        Path target = resolve(relativePath);
        try {
            Files.createDirectories(target.getParent());
            Files.write(target, content);
            return relativePath;
        } catch (IOException e) {
            throw new UncheckedIOException("파일을 저장하지 못했습니다: " + relativePath, e);
        }
    }

    public byte[] read(String relativePath) {
        try {
            return Files.readAllBytes(resolve(relativePath));
        } catch (IOException e) {
            throw new UncheckedIOException("파일을 읽지 못했습니다: " + relativePath, e);
        }
    }

    public void delete(String relativePath) {
        try {
            Files.deleteIfExists(resolve(relativePath));
        } catch (IOException e) {
            throw new UncheckedIOException("파일을 삭제하지 못했습니다: " + relativePath, e);
        }
    }

    /** 저장 루트 밖으로 나가는 경로(../ 등)는 거부한다 */
    private Path resolve(String relativePath) {
        Path path = root.resolve(relativePath).normalize();
        if (!path.startsWith(root)) {
            throw new IllegalArgumentException("저장 경로를 벗어난 파일 경로: " + relativePath);
        }
        return path;
    }
}
