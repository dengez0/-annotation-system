package com.simplelabel.controller;

import com.simplelabel.config.ApiException;
import com.simplelabel.service.AutoLabelService;
import com.simplelabel.service.WorkLogService;
import com.simplelabel.task.TaskService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
public class AutoLabelController {
    private final AutoLabelService autoLabel;
    private final TaskService tasks;
    private final WorkLogService workLog;

    public AutoLabelController(AutoLabelService autoLabel, TaskService tasks, WorkLogService workLog) {
        this.autoLabel = autoLabel; this.tasks = tasks; this.workLog = workLog;
    }

    @PostMapping("/api/auto_label/{main}/{sub}")
    Map<String, Object> start(@PathVariable String main, @PathVariable String sub,
                              @RequestBody Map<String, Object> body, HttpServletRequest request) {
        String model = body.get("model_name") == null ? null : String.valueOf(body.get("model_name"));
        if (model == null || model.isBlank()) throw new ApiException(HttpStatus.BAD_REQUEST, "Model name required");
        String backend = String.valueOf(body.getOrDefault("backend", "auto"));
        String customRepo = body.get("custom_repo") == null ? null : String.valueOf(body.get("custom_repo"));
        double confidence;
        try { confidence = Double.parseDouble(String.valueOf(body.getOrDefault("conf", 0.25))); }
        catch (NumberFormatException exception) { throw new IllegalArgumentException("Invalid confidence"); }
        String taskId = autoLabel.start(main, sub, model, confidence, backend, customRepo);
        workLog.write("AUTO_LABEL_START", request.getRemoteAddr(), main, sub,
                model + " (" + backend + ")", null, null, null);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "started"); result.put("task_id", taskId); result.put("backend", backend);
        return result;
    }

    @GetMapping("/api/task_status/{taskId}")
    Map<String, Object> status(@PathVariable String taskId) {
        Map<String, Object> result = tasks.get(taskId);
        if (result == null) throw new ApiException(HttpStatus.NOT_FOUND, "Task not found");
        return result;
    }

    @PostMapping("/api/cancel_task/{taskId}")
    Map<String, Object> cancel(@PathVariable String taskId, HttpServletRequest request) {
        if (!tasks.cancel(taskId)) throw new ApiException(HttpStatus.NOT_FOUND, "Task not found");
        workLog.write("AUTO_LABEL_CANCEL", request.getRemoteAddr(), null, null, taskId, null, null, null);
        return Map.of("status", "success");
    }
}
