package com.smwu.backend.common.storage;

import com.smwu.backend.common.config.AppProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FileStorageTest {

    @TempDir
    Path root;

    @Test
    void 저장_읽기_삭제() {
        FileStorage storage = storage();
        String path = storage.save("past-exams", "pdf", new byte[]{1, 2, 3});

        assertThat(path).startsWith("past-exams/").endsWith(".pdf");
        assertThat(storage.read(path)).containsExactly(1, 2, 3);
        storage.delete(path);
        assertThat(root.resolve(path)).doesNotExist();
    }

    @Test
    void 저장_루트_밖_경로는_거부한다() {
        assertThatThrownBy(() -> storage().read("../secret.txt"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private FileStorage storage() {
        return new FileStorage(new AppProperties(new AppProperties.Storage(root.toString()), null, null));
    }
}
