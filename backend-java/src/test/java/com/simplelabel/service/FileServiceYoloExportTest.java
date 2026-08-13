package com.simplelabel.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.simplelabel.config.ApiException;
import com.simplelabel.config.AppPaths;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FileServiceYoloExportTest {
    @TempDir
    Path root;

    @Test
    void usesSubmittedLabelOrderForClassesAndAnnotationIds() throws Exception {
        Fixture fixture = fixture();

        Map<String, Object> result = fixture.files.exportYolo("main", "project", List.of("中文 标签", "alpha"));

        Path labels = fixture.root.resolve("labels").resolve("project_labels");
        assertThat(result.get("class_count")).isEqualTo(2);
        assertThat(result.get("labels_dir")).isEqualTo("labels/project_labels");
        assertThat(fixture.project.resolve("labels")).doesNotExist();
        assertThat(Files.readString(labels.resolve("classes.txt"), StandardCharsets.UTF_8))
                .isEqualTo("中文 标签\nalpha\n");
        assertThat(Files.readAllLines(labels.resolve("frame.txt"), StandardCharsets.UTF_8))
                .first().asString().startsWith("1 ");
        assertThat(Files.readAllLines(labels.resolve("frame.txt"), StandardCharsets.UTF_8))
                .element(1).asString().startsWith("0 ");
    }

    @Test
    void rejectsMissingUnknownAndDuplicateLabelsBeforeWritingOutput() throws Exception {
        Fixture fixture = fixture();

        assertThatThrownBy(() -> fixture.files.exportYolo("main", "project", List.of("alpha")))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("incomplete or out of date");
        assertThatThrownBy(() -> fixture.files.exportYolo("main", "project", List.of("alpha", "unknown")))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("incomplete or out of date");
        assertThatThrownBy(() -> fixture.files.exportYolo("main", "project", List.of("alpha", "alpha")))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Duplicate");
        assertThat(fixture.root.resolve("labels").resolve("project_labels")).doesNotExist();
    }

    @Test
    void refusesToOverwriteAnExistingRootDestination() throws Exception {
        Fixture fixture = fixture();
        Path destination = fixture.root.resolve("labels").resolve("project_labels");
        Files.createDirectories(destination);
        Files.writeString(destination.resolve("existing.txt"), "keep me", StandardCharsets.UTF_8);

        assertThatThrownBy(() -> fixture.files.exportYolo("main", "project", List.of("alpha", "中文 标签")))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("already exists");
        assertThat(Files.readString(destination.resolve("existing.txt"), StandardCharsets.UTF_8)).isEqualTo("keep me");
    }

    private Fixture fixture() throws Exception {
        Files.createDirectories(root.resolve("data"));
        Files.createDirectories(root.resolve("models"));
        Files.createDirectories(root.resolve("logs"));
        AppPaths paths = new AppPaths(root.toString());
        ObjectMapper mapper = new ObjectMapper();
        AnnotationService annotations = new AnnotationService(paths, mapper);
        FileService files = new FileService(paths, mapper, annotations);
        Path project = paths.safeDataPath("main", "project");
        Files.createDirectories(project);
        Files.write(project.resolve("frame.jpg"), new byte[]{1});
        Files.writeString(project.resolve("frame.json"), """
                {
                  "imageWidth": 100,
                  "imageHeight": 100,
                  "shapes": [
                    {"label": "alpha", "points": [[10, 10], [30, 30]]},
                    {"label": "中文 标签", "points": [[40, 40], [80, 80]]}
                  ]
                }
                """, StandardCharsets.UTF_8);
        return new Fixture(files, project, root);
    }

    private record Fixture(FileService files, Path project, Path root) { }
}
