package com.simplelabel.task;

import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Service
public class TaskService {
    private final ConcurrentHashMap<String, TaskState> tasks = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newFixedThreadPool(3);

    public String create(String backend) {
        String id = UUID.randomUUID().toString();
        tasks.put(id, new TaskState(backend));
        return id;
    }

    public Map<String, Object> get(String id) {
        TaskState task = tasks.get(id);
        return task == null ? null : task.snapshot();
    }

    public TaskState state(String id) { return tasks.get(id); }
    public void submit(Runnable work) { executor.submit(work); }

    public boolean cancel(String id) {
        TaskState task = tasks.get(id);
        if (task == null) return false;
        task.cancel(); return true;
    }

    @PreDestroy
    public void close() { executor.shutdownNow(); }

    public static final class TaskState {
        private String status = "running";
        private int progress;
        private int total;
        private int processedCount;
        private boolean cancel;
        private boolean cancellationLocked;
        private String error;
        private String backend;
        private String stage = "queued";
        private String operation;
        private String resultId;
        private Map<String, Object> summary = Map.of();

        TaskState(String backend) { this.backend = backend; }
        public synchronized boolean cancelled() { return cancel; }
        public synchronized void cancel() {
            if (cancellationLocked) return;
            cancel = true; status = "cancelled"; stage = "cancelled";
        }
        public synchronized boolean lockCancellation() {
            if (cancel) return false;
            cancellationLocked = true;
            return true;
        }
        public synchronized void total(int value) { total = value; }
        public synchronized void progress(int value, int processed) { progress = value; processedCount = processed; }
        public synchronized void backend(String value) { backend = value; }
        public synchronized void stage(String value) { stage = value; }
        public synchronized void operation(String value) { operation = value; }
        public synchronized void result(String value, Map<String, Object> valueSummary) {
            resultId = value;
            summary = valueSummary == null ? Map.of() : new LinkedHashMap<>(valueSummary);
        }
        public synchronized void complete() { if (!cancel) status = "completed"; }
        public synchronized void fail(Exception exception) {
            status = "failed";
            stage = "failed";
            error = exception.getMessage();
        }
        public synchronized Map<String, Object> snapshot() {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", status); result.put("progress", progress); result.put("total", total);
            result.put("processed_count", processedCount); result.put("cancel", cancel);
            result.put("error", error); result.put("backend", backend);
            result.put("stage", stage); result.put("operation", operation);
            result.put("result_id", resultId); result.put("summary", summary);
            return result;
        }
    }
}
