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
    void movesSeparatedAnnotationAndRestoresItToAnnotating() throws Exception {
        AppPaths paths = paths();
        ObjectMapper mapper = new ObjectMapper();
        AnnotationService annotations = new AnnotationService(paths, mapper);
        FileService files = new FileService(paths, mapper, annotations);
        Path original = paths.safeDataPath("标注中", "day-shift");
        Files.createDirectories(original.resolve("label"));
        Files.write(original.resolve("frame-001.jpg"), new byte[]{1, 2, 3});
        Files.writeString(original.resolve("label/frame-001.json"), "{\"shapes\":[]}");

        Map<String, Object> moveResult = files.moveToCompleted(
                "标注中", "day-shift", List.of("frame-001.jpg"));

        Path moved = paths.safeDataPath("moved image", "day-shift");
        assertThat(moveResult.get("moved")).isEqualTo(1);
        assertThat(moved.resolve("frame-001.jpg")).exists();
        assertThat(moved.resolve("frame-001.json")).exists();
        assertThat(moved.resolve(".move_origins.json")).exists();
        assertThat(original.resolve("frame-001.jpg")).doesNotExist();
        assertThat(original.resolve("label/frame-001.json")).doesNotExist();

        Map<String, Object> result = files.restoreFromCompleted("day-shift", List.of("frame-001.jpg"));

        assertThat(result.get("moved")).isEqualTo(1);
        assertThat(result.get("destination")).isEqualTo("标注中/day-shift");
        assertThat(original.resolve("frame-001.jpg")).exists();
        assertThat(original.resolve("label/frame-001.json")).exists();
        assertThat(moved.resolve("frame-001.jpg")).doesNotExist();
        assertThat(moved.resolve("frame-001.json")).doesNotExist();
        assertThat(moved.resolve(".move_origins.json")).doesNotExist();
    }

    @Test
    void restoresToReviewAfterTaskAdvancesWhileFilesAreMovedOut() throws Exception {
        AppPaths paths = paths();
        ObjectMapper mapper = new ObjectMapper();
        AnnotationService annotations = new AnnotationService(paths, mapper);
        FileService files = new FileService(paths, mapper, annotations);
        Path annotating = paths.safeDataPath("标注中", "night-shift");
        Files.createDirectories(annotating.resolve("label"));
        Files.write(annotating.resolve("frame-002.jpg"), new byte[]{1});
        Files.writeString(annotating.resolve("label/frame-002.json"), "{\"shapes\":[]}");
        files.moveToCompleted("标注中", "night-shift", List.of("frame-002.jpg"));
        Files.createDirectories(paths.safeDataPath("待检查"));
        Files.move(annotating, paths.safeDataPath("待检查", "night-shift"));

        Map<String, Object> result = files.restoreFromCompleted("night-shift", List.of("frame-002.jpg"));

        Path review = paths.safeDataPath("待检查", "night-shift");
        assertThat(result.get("destination")).isEqualTo("待检查/night-shift");
        assertThat(review.resolve("frame-002.jpg")).exists();
        assertThat(review.resolve("label/frame-002.json")).exists();
    }

    @Test
    void conflictLeavesSourcePairUntouched() throws Exception {
        AppPaths paths = paths();
        ObjectMapper mapper = new ObjectMapper();
        AnnotationService annotations = new AnnotationService(paths, mapper);
        FileService files = new FileService(paths, mapper, annotations);
        Path moved = paths.safeDataPath("moved image", "day-shift");
        Path original = paths.safeDataPath("标注中", "day-shift");
        Files.createDirectories(moved);
        Files.createDirectories(original.resolve("label"));
        Files.write(moved.resolve("frame-001.jpg"), new byte[]{1});
        Files.writeString(moved.resolve("frame-001.json"), "{\"shapes\":[]}");
        Files.writeString(moved.resolve(".move_origins.json"), "{\"frame-001.jpg\":\"标注中\"}");
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
