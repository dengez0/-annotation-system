package com.simplelabel.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.simplelabel.config.AppPaths;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AnnotationUploadTest {
    @TempDir Path root;

    @Test
    void publishesFolderToUnlabeledAndSeparatesUploadedJson() throws Exception {
        AppPaths paths = new AppPaths(root.toString());
        Files.createDirectories(paths.data());
        Files.createDirectories(paths.safeDataPath("未标注"));
        AnnotationService annotations = new AnnotationService(paths, new ObjectMapper());
        FileService files = new FileService(paths, new ObjectMapper(), annotations);

        files.uploadAnnotation(List.of(
                        new MockMultipartFile("files[]", "frame.jpg", "image/jpeg", "image".getBytes()),
                        new MockMultipartFile("files[]", "frame.json", "application/json", "{\"shapes\":[]}".getBytes())),
                "shift-c", "upload-1", "[\"shift-c/frame.jpg\",\"shift-c/frame.json\"]", true);

        assertThat(paths.safeDataPath("未标注", "shift-c", "frame.jpg")).isRegularFile();
        assertThat(paths.safeDataPath("未标注", "shift-c", "label", "frame.json")).isRegularFile();
        assertThat(paths.safeDataPath("未标注", "shift-c", "frame.json")).doesNotExist();
        assertThat(annotations.listImages("未标注", "shift-c").getFirst().get("processed")).isEqualTo(true);
    }
}
