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
    void close() { executor.shutdownNow(); }

    public static final class TaskState {
        private String status = "running";
        private int progress;
        private int total;
        private int processedCount;
        private boolean cancel;
        private String error;
        private String backend;

        TaskState(String backend) { this.backend = backend; }
        public synchronized boolean cancelled() { return cancel; }
        public synchronized void cancel() { cancel = true; status = "cancelled"; }
        public synchronized void total(int value) { total = value; }
        public synchronized void progress(int value, int processed) { progress = value; processedCount = processed; }
        public synchronized void backend(String value) { backend = value; }
        public synchronized void complete() { if (!cancel) status = "completed"; }
        public synchronized void fail(Exception exception) { status = "failed"; error = exception.getMessage(); }
        public synchronized Map<String, Object> snapshot() {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", status); result.put("progress", progress); result.put("total", total);
            result.put("processed_count", processedCount); result.put("cancel", cancel);
            result.put("error", error); result.put("backend", backend);
            return result;
        }
    }
}
