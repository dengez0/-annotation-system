package com.simplelabel.service;

import com.simplelabel.config.ApiException;
import com.simplelabel.config.AppPaths;
import jakarta.annotation.PostConstruct;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class WorkflowService {
    private final AppPaths paths;
    private final Map<String, Object> taskLocks = new ConcurrentHashMap<>();

    public WorkflowService(AppPaths paths) { this.paths = paths; }

    @PostConstruct
    void initialize() throws IOException {
        for (WorkflowState state : WorkflowState.values()) {
            Files.createDirectories(paths.safeDataPath(state.directory()));
        }
        Files.createDirectories(paths.safeDataPath(".uploads"));
        Files.createDirectories(paths.safeDataPath(".workflow-staging"));
    }

    public boolean isManaged(String main) { return WorkflowState.fromDirectory(main).isPresent(); }

    public boolean isLegacy(String main) {
        return !isManaged(main) && !main.startsWith(".");
    }

    public Path task(WorkflowState state, String name) {
        AnnotationService.validateComponent(name);
        return paths.safeDataPath(state.directory(), name);
    }

    public void requireUniqueTaskName(String name) {
        AnnotationService.validateComponent(name);
        for (WorkflowState state : WorkflowState.values()) {
            if (Files.exists(task(state, name))) {
                throw new ApiException(HttpStatus.CONFLICT,
                        "Task already exists in " + state.directory() + ": " + name);
            }
        }
    }

    public SaveResult saveWithAutomaticStart(String main, String taskName, SaveOperation operation) throws IOException {
        WorkflowState state = WorkflowState.fromDirectory(main)
                .orElseThrow(() -> new ApiException(HttpStatus.FORBIDDEN, "Legacy projects are read-only"));
        Object lock = taskLocks.computeIfAbsent(taskName, ignored -> new Object());
        synchronized (lock) {
            if (state != WorkflowState.UNLABELED) {
                operation.save(main);
                return new SaveResult(main, false);
            }
            Path source = task(WorkflowState.UNLABELED, taskName);
            Path destination = task(WorkflowState.ANNOTATING, taskName);
            if (!Files.isDirectory(source)) {
                if (Files.isDirectory(destination)) {
                    operation.save(WorkflowState.ANNOTATING.directory());
                    return new SaveResult(WorkflowState.ANNOTATING.directory(), true);
                }
                throw new ApiException(HttpStatus.NOT_FOUND, "Task not found");
            }
            if (Files.exists(destination)) throw new ApiException(HttpStatus.CONFLICT, "Task already exists in 标注中");
            moveDirectory(source, destination);
            try {
                operation.save(WorkflowState.ANNOTATING.directory());
                return new SaveResult(WorkflowState.ANNOTATING.directory(), true);
            } catch (IOException | RuntimeException exception) {
                try { moveDirectory(destination, source); } catch (IOException rollback) { exception.addSuppressed(rollback); }
                throw exception;
            }
        }
    }

    public TransitionResult transition(String taskName, WorkflowState from, WorkflowState to,
                                       boolean allowIncomplete, int images, int annotated) throws IOException {
        if (!((from == WorkflowState.ANNOTATING && to == WorkflowState.REVIEW)
                || (from == WorkflowState.REVIEW && to == WorkflowState.COMPLETED))) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Invalid workflow transition");
        }
        if (annotated < images && !allowIncomplete) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "Task has " + (images - annotated) + " image(s) without JSON");
        }
        Object lock = taskLocks.computeIfAbsent(taskName, ignored -> new Object());
        synchronized (lock) {
            Path source = task(from, taskName);
            Path destination = task(to, taskName);
            if (!Files.isDirectory(source)) throw new ApiException(HttpStatus.NOT_FOUND, "Task not found");
            if (Files.exists(destination)) throw new ApiException(HttpStatus.CONFLICT, "Destination task already exists");
            if (to == WorkflowState.COMPLETED) completeWithFlattening(source, destination, taskName);
            else moveDirectory(source, destination);
        }
        return new TransitionResult(from.id(), to.id(), to.directory(), images, annotated);
    }

    private void completeWithFlattening(Path source, Path destination, String taskName) throws IOException {
        Path staging = paths.safeDataPath(".workflow-staging", taskName + "-" + UUID.randomUUID());
        moveDirectory(source, staging);
        try {
            Path labels = staging.resolve("label");
            if (Files.isDirectory(labels)) {
                try (var stream = Files.list(labels)) {
                    for (Path json : stream.filter(Files::isRegularFile).toList()) {
                        Path target = staging.resolve(json.getFileName());
                        if (Files.exists(target)) throw new IOException("JSON conflict while completing: " + json.getFileName());
                        Files.move(json, target);
                    }
                }
                try (var stream = Files.list(labels)) {
                    if (stream.findAny().isEmpty()) Files.delete(labels);
                }
            }
            moveDirectory(staging, destination);
        } catch (IOException | RuntimeException exception) {
            try {
                Path labels = staging.resolve("label");
                Files.createDirectories(labels);
                try (var stream = Files.list(staging)) {
                    for (Path json : stream.filter(path -> Files.isRegularFile(path)
                            && path.getFileName().toString().toLowerCase().endsWith(".json")).toList()) {
                        Files.move(json, labels.resolve(json.getFileName()), StandardCopyOption.REPLACE_EXISTING);
                    }
                }
                moveDirectory(staging, source);
            } catch (IOException rollback) { exception.addSuppressed(rollback); }
            throw exception;
        }
    }

    private static void moveDirectory(Path source, Path destination) throws IOException {
        Files.createDirectories(destination.getParent());
        try { Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE); }
        catch (AtomicMoveNotSupportedException exception) { Files.move(source, destination); }
    }

    @FunctionalInterface public interface SaveOperation { void save(String actualMain) throws IOException; }
    public record SaveResult(String main, boolean stateChanged) { }
    public record TransitionResult(String from, String to, String directory, int images, int annotated) { }
}
