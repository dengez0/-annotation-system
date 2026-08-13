package com.simplelabel.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.simplelabel.config.AppPaths;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class FileServiceRestoreTest {
    @TempDir
    Path root;

    @Test
    void restoresImageAndMatchingJsonToOriginalAnnotationFolder() throws Exception {
        AppPaths paths = paths();
        ObjectMapper mapper = new ObjectMapper();
        AnnotationService annotations = new AnnotationService(paths, mapper);
        FileService files = new FileService(paths, mapper, annotations);
        Path moved = paths.safeDataPath("moved image", "day-shift");
        Files.createDirectories(moved);
        Files.write(moved.resolve("frame-001.jpg"), new byte[]{1, 2, 3});
        Files.writeString(moved.resolve("frame-001.json"), "{\"shapes\":[]}");

        Map<String, Object> result = files.restoreFromCompleted("day-shift", List.of("frame-001.jpg"));

        Path original = paths.safeDataPath("annotation flies", "day-shift");
        assertThat(result.get("moved")).isEqualTo(1);
        assertThat(result.get("destination")).isEqualTo("annotation flies/day-shift");
        assertThat(original.resolve("frame-001.jpg")).exists();
        assertThat(original.resolve("frame-001.json")).exists();
        assertThat(moved.resolve("frame-001.jpg")).doesNotExist();
        assertThat(moved.resolve("frame-001.json")).doesNotExist();
    }

    @Test
    void conflictLeavesSourcePairUntouched() throws Exception {
        AppPaths paths = paths();
        ObjectMapper mapper = new ObjectMapper();
        AnnotationService annotations = new AnnotationService(paths, mapper);
        FileService files = new FileService(paths, mapper, annotations);
        Path moved = paths.safeDataPath("moved image", "day-shift");
        Path original = paths.safeDataPath("annotation flies", "day-shift");
        Files.createDirectories(moved);
        Files.createDirectories(original);
        Files.write(moved.resolve("frame-001.jpg"), new byte[]{1});
        Files.writeString(moved.resolve("frame-001.json"), "{\"shapes\":[]}");
        Files.write(original.resolve("frame-001.jpg"), new byte[]{9});

        Map<String, Object> result = files.restoreFromCompleted("day-shift", List.of("frame-001.jpg"));

        assertThat(result.get("moved")).isEqualTo(0);
        assertThat((List<?>) result.get("skipped")).hasSize(1);
        assertThat(moved.resolve("frame-001.jpg")).exists();
        assertThat(moved.resolve("frame-001.json")).exists();
    }

    private AppPaths paths() throws Exception {
        Files.createDirectories(root.resolve("data"));
        Files.createDirectories(root.resolve("models"));
        Files.createDirectories(root.resolve("logs"));
        return new AppPaths(root.toString());
    }
}
