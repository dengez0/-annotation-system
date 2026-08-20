package com.simplelabel.service;

import java.util.Arrays;
import java.util.Optional;

public enum WorkflowState {
    UNLABELED("unlabeled", "未标注"),
    ANNOTATING("annotating", "标注中"),
    REVIEW("review", "待检查"),
    COMPLETED("completed", "已完成");

    private final String id;
    private final String directory;

    WorkflowState(String id, String directory) {
        this.id = id;
        this.directory = directory;
    }

    public String id() { return id; }
    public String directory() { return directory; }

    public static Optional<WorkflowState> fromDirectory(String directory) {
        return Arrays.stream(values()).filter(value -> value.directory.equals(directory)).findFirst();
    }

    public static WorkflowState fromId(String id) {
        return Arrays.stream(values()).filter(value -> value.id.equals(id)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown workflow state: " + id));
    }
}
