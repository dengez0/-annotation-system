package com.simplelabel.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.simplelabel.config.ApiException;
import com.simplelabel.config.AppPaths;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkflowServiceTest {
    @TempDir Path root;
    private AppPaths paths;
    private WorkflowService workflow;
    private AnnotationService annotations;

    @BeforeEach
    void setUp() throws Exception {
        paths = new AppPaths(root.toString());
        Files.createDirectories(paths.data());
        Files.createDirectories(paths.logs());
        Files.createDirectories(paths.admin());
        Files.createDirectories(paths.models());
        Files.createDirectories(paths.processed());
        workflow = new WorkflowService(paths);
        workflow.initialize();
        annotations = new AnnotationService(paths, new ObjectMapper());
    }

    @Test
    void firstSaveStartsTaskAndCompletionFlattensLabels() throws Exception {
        Path uploaded = paths.safeDataPath("未标注", "shift-a");
        Files.createDirectories(uploaded);
        Files.writeString(uploaded.resolve("frame-001.jpg"), "image");
        ObjectNode json = new ObjectMapper().createObjectNode();
        json.putArray("shapes");

        WorkflowService.SaveResult started = workflow.saveWithAutomaticStart("未标注", "shift-a",
                main -> annotations.saveAnnotation(main, "shift-a", "frame-001.jpg", json));

        assertThat(started.main()).isEqualTo("标注中");
        assertThat(started.stateChanged()).isTrue();
        assertThat(paths.safeDataPath("未标注", "shift-a")).doesNotExist();
        assertThat(paths.safeDataPath("标注中", "shift-a", "frame-001.jpg")).isRegularFile();
        assertThat(paths.safeDataPath("标注中", "shift-a", "label", "frame-001.json")).isRegularFile();

        workflow.transition("shift-a", WorkflowState.ANNOTATING, WorkflowState.REVIEW, false, 1, 1);
        assertThat(paths.safeDataPath("待检查", "shift-a", "label", "frame-001.json")).isRegularFile();

        workflow.transition("shift-a", WorkflowState.REVIEW, WorkflowState.COMPLETED, false, 1, 1);
        assertThat(paths.safeDataPath("已完成", "shift-a", "frame-001.jpg")).isRegularFile();
        assertThat(paths.safeDataPath("已完成", "shift-a", "frame-001.json")).isRegularFile();
        assertThat(paths.safeDataPath("已完成", "shift-a", "label")).doesNotExist();
    }

    @Test
    void incompleteTaskRequiresExplicitOverrideAtBothDoneSteps() throws Exception {
        Path task = paths.safeDataPath("标注中", "shift-b");
        Files.createDirectories(task.resolve("label"));
        Files.writeString(task.resolve("frame.jpg"), "image");

        assertThatThrownBy(() -> workflow.transition("shift-b", WorkflowState.ANNOTATING,
                WorkflowState.REVIEW, false, 1, 0)).isInstanceOf(ApiException.class);
        workflow.transition("shift-b", WorkflowState.ANNOTATING, WorkflowState.REVIEW, true, 1, 0);

        assertThatThrownBy(() -> workflow.transition("shift-b", WorkflowState.REVIEW,
                WorkflowState.COMPLETED, false, 1, 0)).isInstanceOf(ApiException.class);
        workflow.transition("shift-b", WorkflowState.REVIEW, WorkflowState.COMPLETED, true, 1, 0);
        assertThat(paths.safeDataPath("已完成", "shift-b", "frame.jpg")).isRegularFile();
    }

    @Test
    void legacyProjectsReadSiblingJsonWithoutChangingIt() throws Exception {
        Path legacy = paths.safeDataPath("annotation flies", "old-task");
        Files.createDirectories(legacy);
        Files.writeString(legacy.resolve("old.jpg"), "image");
        Files.writeString(legacy.resolve("old.json"), "{\"shapes\":[]}");

        assertThat(annotations.listImages("annotation flies", "old-task").getFirst().get("processed")).isEqualTo(true);
        assertThat(annotations.annotationPath("annotation flies", "old-task", "old.jpg"))
                .isEqualTo(legacy.resolve("old.json"));
    }
}
